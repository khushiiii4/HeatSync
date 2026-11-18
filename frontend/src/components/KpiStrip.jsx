import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, CloudOff, Flame, Hourglass, Thermometer, Zap } from 'lucide-react';
import { useAlerts, useCoolingSummary, useRacks, useSchedulerStatus, useWorkloadSummary } from '../api/sources';
import { bandFor } from '../lib/colorScale';
import { fmtKw, fmtTemp } from '../lib/format';
import { Skeleton, cx } from './ui';

function Kpi({ label, icon: Icon, tone = 'text-cyan', value, unit, sub, subTone, source, to }) {
  const loading = source?.isLoading && !source?.hasData;
  const failing = source?.stale || source?.down;
  const body = (
    <div className={cx('card-surface h-full p-5 transition-colors', to && 'hover:border-ink-500')}>
      <div className="flex items-center justify-between">
        <p className="text-xs font-semibold uppercase tracking-[0.12em] text-fg-mute">{label}</p>
        <div className="flex items-center gap-2">
          {failing && (
            <span title={`${source.service || 'Data source'} unavailable — showing the last value`} className="text-warn">
              <CloudOff className="h-4 w-4" aria-label="Source unavailable" />
            </span>
          )}
          <Icon className={cx('h-[18px] w-[18px]', tone)} aria-hidden />
        </div>
      </div>
      {loading ? (
        <Skeleton className="mt-3 h-10 w-28" />
      ) : value == null ? (
        <p className="mt-2 text-[34px] font-semibold leading-none text-fg-faint">—</p>
      ) : (
        <p className="num mt-2 text-[38px] font-semibold leading-none tracking-tight text-fg">
          {value}
          {unit && <span className="ml-1 text-lg font-medium text-fg-mute">{unit}</span>}
        </p>
      )}
      <p className={cx('mt-2 text-sm leading-snug', subTone || 'text-fg-mute')}>{loading ? ' ' : sub}</p>
    </div>
  );
  return to ? (
    <Link to={to} className="block rounded-[14px]">
      {body}
    </Link>
  ) : (
    body
  );
}

/** Averages and extremes over the racks that actually reported a temperature. */
export function summariseRacks(racks = []) {
  const live = racks.filter((r) => r.tempC != null && r.sensorStatus !== 'STALE');
  if (!live.length) return { avg: null, hottest: null, count: 0 };
  const avg = live.reduce((s, r) => s + Number(r.tempC), 0) / live.length;
  const hottest = live.reduce((a, b) => (Number(b.tempC) > Number(a.tempC) ? b : a));
  return { avg, hottest, count: live.length };
}

export function KpiStrip() {
  const racks = useRacks();
  const alerts = useAlerts('ACTIVE', 50);
  const cooling = useCoolingSummary();
  const sched = useSchedulerStatus();
  const wl = useWorkloadSummary();

  const r = useMemo(() => summariseRacks(racks.data), [racks.data]);
  const alertCount = alerts.data?.length;
  const worst = useMemo(() => (alerts.data || []).reduce((acc, a) => (['CRITICAL', 'WARNING', 'WATCH'].indexOf(a.level) < ['CRITICAL', 'WARNING', 'WATCH'].indexOf(acc) || acc === null ? a.level : acc), null), [alerts.data]);
  const pending = sched.hasData ? sched.data.pendingWorkloads : wl.hasData ? wl.data.pendingCount : null;
  const band = r.hottest ? bandFor(r.hottest.tempC) : null;

  return (
    <div className="grid grid-cols-2 gap-4 xl:grid-cols-5">
      <Kpi label="Average temperature" icon={Thermometer} value={r.avg != null ? fmtTemp(r.avg) : null} unit="°C" sub={r.count ? `${r.count} racks · target 26.0 °C` : 'no readings yet'} source={racks} />
      <Kpi
        label="Hottest rack"
        icon={Flame}
        tone="text-orange"
        value={r.hottest ? r.hottest.rackId : null}
        sub={r.hottest ? `${fmtTemp(r.hottest.tempC)} °C · forecast ${fmtTemp(r.hottest.forecast60C)} °C` : 'no readings yet'}
        subTone={band && band.id !== 'cool' && band.id !== 'normal' ? 'text-warn' : undefined}
        source={racks}
        to={r.hottest ? `/racks/${r.hottest.rackId}` : undefined}
      />
      <Kpi
        label="Active alerts"
        icon={AlertTriangle}
        tone={alertCount ? 'text-orange' : 'text-ok'}
        value={alertCount ?? null}
        sub={alertCount ? `highest level ${worst}` : 'all racks inside limits'}
        subTone={alertCount ? 'text-orange' : 'text-ok'}
        source={alerts}
      />
      <Kpi
        label="Cooling power"
        icon={Zap}
        tone="text-teal"
        value={cooling.hasData ? Number(cooling.data.totalPowerKw).toFixed(2) : null}
        unit="kW"
        sub={cooling.hasData ? `${Number(cooling.data.savingPct).toFixed(0)}% under baseline` : 'waiting for the controller'}
        subTone="text-teal"
        source={cooling}
        to="/cooling"
      />
      <Kpi
        label="Pending workloads"
        icon={Hourglass}
        tone={pending ? 'text-warn' : 'text-cyan'}
        value={pending}
        sub={sched.hasData && sched.data.capacityShortage ? 'capacity shortage: no rack fits' : pending ? 'waiting for placement' : 'everything is placed'}
        subTone={pending ? 'text-warn' : undefined}
        source={sched.hasData || sched.down ? sched : wl}
        to="/workloads"
      />
    </div>
  );
}
