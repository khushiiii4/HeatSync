import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { BellRing, ShieldCheck } from 'lucide-react';
import { useAlerts } from '../api/sources';
import { compareRisk } from '../lib/colorScale';
import { fmtClock, fmtTemp } from '../lib/format';
import { Panel } from './Panel';
import { EmptyState, Skeleton, Tag, levelTone, cx } from './ui';

const BAR = { WATCH: 'bg-warn', WARNING: 'bg-orange', CRITICAL: 'bg-crit' };

export function AlertRow({ alert }) {
  return (
    <li className="relative flex gap-3 overflow-hidden rounded-xl border border-ink-600 bg-ink-800/70 py-3 pl-5 pr-4">
      <span className={cx('absolute inset-y-0 left-0 w-1.5', BAR[alert.level] || 'bg-ink-500')} aria-hidden />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <Link to={`/racks/${alert.rackId}`} className="font-mono text-base font-semibold text-fg hover:text-cyan">
            {alert.rackId}
          </Link>
          <Tag tone={levelTone(alert.level)}>{alert.level}</Tag>
          <Tag tone={alert.basis === 'PREDICTED' ? 'blue' : 'neutral'} title={alert.basis === 'PREDICTED' ? 'Raised by the 60 s forecast before the temperature got there' : 'Raised by the measured temperature'}>
            {alert.basis}
          </Tag>
          <span className="ml-auto text-xs text-fg-mute">{alert.zoneId}</span>
        </div>
        <p className="num mt-1.5 text-sm text-fg-soft">
          Now <span className="font-semibold text-fg">{fmtTemp(alert.tempC)} °C</span> · forecast in 60 s <span className="font-semibold text-fg">{fmtTemp(alert.forecastC)} °C</span>
        </p>
        <p className="num mt-0.5 text-xs text-fg-mute">Raised {fmtClock(alert.raisedAt)}</p>
      </div>
    </li>
  );
}

export function AlertList() {
  const alerts = useAlerts('ACTIVE', 50);
  const sorted = useMemo(
    () => [...(alerts.data || [])].sort((a, b) => compareRisk(a.level, b.level) || Date.parse(b.raisedAt) - Date.parse(a.raisedAt)),
    [alerts.data],
  );

  return (
    <Panel
      title="Active alerts"
      subtitle={alerts.hasData ? `${sorted.length} active` : undefined}
      sources={[alerts]}
      skeleton={
        <div className="space-y-3">
          <Skeleton className="h-[78px]" />
          <Skeleton className="h-[78px]" />
        </div>
      }
      actions={sorted.length > 0 && <BellRing className="h-4 w-4 text-orange" aria-hidden />}
    >
      {sorted.length === 0 ? (
        <EmptyState icon={ShieldCheck} title="No active alerts" body="Every rack is inside its limits and no hotspot is forecast in the next 60 seconds." />
      ) : (
        <ul className="max-h-[340px] space-y-2.5 overflow-y-auto pr-1">
          {sorted.map((a) => (
            <AlertRow key={a.id} alert={a} />
          ))}
        </ul>
      )}
    </Panel>
  );
}
