import { Component } from 'react';
import { AlertOctagon, CloudOff, RefreshCw } from 'lucide-react';
import { combineSources } from '../api/usePolled';
import { fmtClock } from '../lib/format';
import { Button, Skeleton, cx } from './ui';

/** Each panel has its own error boundary: a render failure in one panel never takes down the page (plan 9.5). */
export class PanelBoundary extends Component {
  state = { error: null };

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error) {
    // eslint-disable-next-line no-console
    console.error(`Panel "${this.props.title}" failed to render`, error);
  }

  render() {
    if (this.state.error) {
      return (
        <div className="flex flex-col items-center gap-2 px-6 py-10 text-center" role="alert">
          <AlertOctagon className="h-6 w-6 text-crit" aria-hidden />
          <p className="font-medium text-fg">This panel could not be displayed</p>
          <p className="max-w-sm text-sm text-fg-mute">The rest of the dashboard is unaffected.</p>
          <Button size="sm" onClick={() => this.setState({ error: null })}>
            Try again
          </Button>
        </div>
      );
    }
    return this.props.children;
  }
}

const SERVICE_LABEL = {
  'TELEMETRY-SERVICE': 'Telemetry',
  'SCHEDULER-SERVICE': 'Scheduler',
  'COOLING-CONTROLLER-SERVICE': 'Cooling controller',
  'WORKLOAD-SERVICE': 'Workload service',
  'SIMULATOR-SERVICE': 'Simulator',
  'API-GATEWAY': 'Gateway',
};

const labelOf = (s) => SERVICE_LABEL[s.service] || 'Service';

/**
 * A dashboard panel fed by one or more polled sources.
 *  - first load: skeleton;
 *  - failure with no data yet: a clear error with Retry;
 *  - failure after data: the last data stays, dimmed slightly, with a stale badge that names the service and shows
 *    the last SERVER timestamp ("Scheduler unavailable, last update 12:41:05");
 *  - success but the data's own server timestamp lags (frozen): a quieter "data not advancing" badge.
 */
export function Panel({ title, subtitle, sources = [], actions, children, className = '', bodyClass = '', skeleton, flush = false }) {
  const status = combineSources(sources);
  const stale = sources.filter((s) => s.stale);
  const frozen = sources.filter((s) => s.frozen);
  const failedNoData = status.allDown;
  const down = sources.filter((s) => s.down);

  let badge = null;
  const unavailable = [...stale, ...down];
  if (unavailable.length && !failedNoData) {
    const names = [...new Set(unavailable.map(labelOf))].join(' and ');
    const last = status.lastServerTime ? `, last update ${fmtClock(status.lastServerTime)}` : '';
    badge = (
      <span className="inline-flex items-center gap-1.5 rounded-md border border-warn/40 bg-warn/10 px-2 py-0.5 text-xs font-medium text-warn" role="status">
        <CloudOff className="h-3.5 w-3.5" aria-hidden />
        {names} unavailable{last}
      </span>
    );
  } else if (frozen.length) {
    badge = (
      <span className="inline-flex items-center gap-1.5 rounded-md border border-warn/40 bg-warn/10 px-2 py-0.5 text-xs font-medium text-warn" role="status">
        <CloudOff className="h-3.5 w-3.5" aria-hidden />
        Data not advancing, last update {fmtClock(status.lastServerTime)}
      </span>
    );
  }

  return (
    <section className={cx('card-surface flex min-w-0 flex-col', className)} aria-label={title}>
      <header className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 border-b border-ink-600/80 px-5 py-3.5">
        <div className="min-w-0">
          <h2 className="truncate text-[15px] font-semibold tracking-wide text-fg">{title}</h2>
          {subtitle && <p className="truncate text-xs text-fg-mute">{subtitle}</p>}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {badge}
          {actions}
        </div>
      </header>
      <div className={cx('min-h-0 flex-1', flush ? '' : 'p-5', stale.length || frozen.length ? 'opacity-90' : '', bodyClass)}>
        <PanelBoundary title={title}>
          {status.loading ? (
            skeleton || <DefaultSkeleton />
          ) : failedNoData ? (
            <div className="flex flex-col items-center gap-2 px-4 py-8 text-center" role="alert">
              <CloudOff className="h-6 w-6 text-warn" aria-hidden />
              <p className="font-medium text-fg">{[...new Set(status.failing.filter((s) => s.down).map(labelOf))].join(' and ')} unavailable</p>
              <p className="max-w-sm text-sm text-fg-mute">No data has been received yet. The panel retries automatically.</p>
              <Button size="sm" onClick={() => status.failing.forEach((s) => s.refetch())}>
                <RefreshCw className="h-4 w-4" aria-hidden /> Retry now
              </Button>
            </div>
          ) : (
            children
          )}
        </PanelBoundary>
      </div>
    </section>
  );
}

export function DefaultSkeleton() {
  return (
    <div className="space-y-3" aria-busy="true" aria-label="Loading">
      <Skeleton className="h-5 w-1/3" />
      <Skeleton className="h-16 w-full" />
      <Skeleton className="h-16 w-full" />
    </div>
  );
}
