import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Fan, Zap } from 'lucide-react';
import { useCoolingSummary, useCoolingUnits } from '../api/sources';
import { fmtKw } from '../lib/format';
import { Panel } from './Panel';
import { Skeleton, Tag, cx } from './ui';

const MODE_TONE = { AUTO: 'cyan', MANUAL: 'warn', FAILSAFE: 'crit' };

function stateOf(unit) {
  if (unit.health === 'FAULT') return { label: 'Unit fault', tone: 'crit', ring: 'border-crit/50 shadow-[0_0_0_1px_rgb(248_113_113/0.25)_inset]' };
  if (unit.mode === 'FAILSAFE') return { label: 'Fail-safe', tone: 'crit', ring: 'border-crit/40' };
  if (unit.mode === 'MANUAL') return { label: 'Manual hold', tone: 'warn', ring: 'border-warn/40' };
  if (unit.outputPct >= 90) return { label: 'Full output', tone: 'orange', ring: 'border-orange/40' };
  return { label: 'Operating', tone: 'ok', ring: 'border-ink-600' };
}

export function ZoneCard({ unit }) {
  const st = stateOf(unit);
  const fault = unit.health === 'FAULT';
  return (
    <div className={cx('rounded-xl border bg-ink-800/70 p-4', st.ring)}>
      <div className="flex items-center justify-between">
        <p className="text-[15px] font-semibold text-fg">{unit.zoneId}</p>
        <Tag tone={st.tone}>{st.label}</Tag>
      </div>
      <div className="mt-3 flex items-end justify-between">
        <p className="num text-[34px] font-semibold leading-none tracking-tight text-fg">
          {unit.outputPct}
          <span className="ml-0.5 text-lg font-medium text-fg-mute">%</span>
        </p>
        <Fan className={cx('h-6 w-6', fault ? 'text-crit' : 'text-cyan/80')} aria-hidden />
      </div>
      <div className="mt-3 h-2 overflow-hidden rounded-full bg-ink-900" role="progressbar" aria-valuenow={unit.outputPct} aria-valuemin={0} aria-valuemax={100} aria-label={`${unit.zoneId} cooling output`}>
        <div className={cx('h-full rounded-full transition-[width] duration-500', fault ? 'bg-crit' : unit.outputPct >= 90 ? 'bg-orange' : 'bg-gradient-to-r from-teal to-cyan')} style={{ width: `${unit.outputPct}%` }} />
      </div>
      <dl className="num mt-3 grid grid-cols-2 gap-x-3 gap-y-1 text-xs text-fg-mute">
        <dt>Mode</dt>
        <dd className="text-right font-medium text-fg-soft">
          <Tag tone={MODE_TONE[unit.mode] || 'neutral'} className="!py-0">{unit.mode}</Tag>
        </dd>
        <dt>Target</dt>
        <dd className="text-right font-medium text-fg-soft">{Number(unit.targetTempC).toFixed(1)} °C</dd>
        <dt>Floor</dt>
        <dd className="text-right font-medium text-fg-soft">{unit.floorPct}%</dd>
        <dt>Power</dt>
        <dd className="text-right font-medium text-fg-soft">{fmtKw(unit.estimatedPowerKw)}</dd>
      </dl>
    </div>
  );
}

export function CoolingZoneCards() {
  const units = useCoolingUnits();
  const summary = useCoolingSummary();
  const sorted = useMemo(() => [...(units.data || [])].sort((a, b) => a.zoneId.localeCompare(b.zoneId)), [units.data]);

  return (
    <Panel
      title="Cooling zones"
      subtitle="Output set by the proportional controller"
      sources={[units, summary]}
      skeleton={
        <div className="grid grid-cols-2 gap-3 min-[1700px]:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-[200px] rounded-xl" />
          ))}
        </div>
      }
      actions={
        <Link to="/cooling" className="text-xs font-medium text-fg-mute transition hover:text-cyan">
          Open cooling →
        </Link>
      }
    >
      <div className="grid grid-cols-2 gap-3 min-[1700px]:grid-cols-4">
        {sorted.map((u) => (
          <ZoneCard key={u.zoneId} unit={u} />
        ))}
      </div>
      {summary.hasData && (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-x-6 gap-y-2 rounded-xl border border-teal/25 bg-teal/5 px-4 py-3">
          <p className="flex items-center gap-2 text-sm text-fg-soft">
            <Zap className="h-4 w-4 text-teal" aria-hidden />
            <span>
              Modelled cooling power <span className="num font-semibold text-fg">{fmtKw(summary.data.totalPowerKw)}</span> against a static 70% policy of{' '}
              <span className="num font-semibold text-fg">{fmtKw(summary.data.baselineTotalKw)}</span>
            </span>
          </p>
          <p className="num text-sm font-semibold text-teal">
            {Number(summary.data.savingPct).toFixed(0)}% saving <span className="font-normal text-fg-mute">· modelled, not measured</span>
          </p>
        </div>
      )}
    </Panel>
  );
}
