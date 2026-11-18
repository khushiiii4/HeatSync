import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { ArrowDown, ArrowUp, Minus, Thermometer } from 'lucide-react';
import { useCoolingUnits, useRacks, useTopology } from '../api/sources';
import { SCALE, STALE_FILL, fillFor, outlineFor, trendDirection } from '../lib/colorScale';
import { fmtTemp } from '../lib/format';
import { useFlashing } from '../state/flash';
import { Panel, DefaultSkeleton } from './Panel';
import { Skeleton, Tag, cx } from './ui';

const TREND = {
  up: { Icon: ArrowUp, label: 'rising', cls: 'text-orange' },
  down: { Icon: ArrowDown, label: 'falling', cls: 'text-sky' },
  flat: { Icon: Minus, label: 'steady', cls: 'text-fg-soft/70' },
};

export function RackCell({ rack, reading, flash }) {
  const stale = reading?.sensorStatus === 'STALE';
  const maintenance = rack?.status === 'MAINTENANCE';
  const temp = reading?.tempC;
  const fill = fillFor(temp, { stale: stale || temp == null });
  const outline = stale ? null : outlineFor(reading?.riskLevel);
  const dir = trendDirection(reading?.trendCPerMin);
  const { Icon, label, cls } = TREND[dir];
  const cpu = reading?.cpuLoadPct;
  const forecast = reading?.forecast60C;

  const aria = `Rack ${rack.id}, ${temp != null ? `${fmtTemp(temp)} degrees` : 'no reading'}, ${reading?.riskLevel || 'unknown'} risk, ${label}${stale ? ', sensor stale' : ''}${maintenance ? ', in maintenance' : ''}`;

  return (
    <Link
      to={`/racks/${rack.id}`}
      aria-label={aria}
      className={cx(
        'group relative flex min-h-[128px] flex-col justify-between overflow-hidden rounded-xl p-3 text-white transition-transform duration-150 hover:-translate-y-0.5',
        maintenance && 'hatch',
        flash && 'flash',
      )}
      style={{
        background: `linear-gradient(160deg, ${fill}, ${fill}d9)`,
        boxShadow: outline ? `0 0 0 2.5px ${outline}, 0 8px 24px -12px ${outline}` : 'inset 0 0 0 1px rgb(255 255 255 / 0.10)',
      }}
    >
      <div className="flex items-start justify-between">
        <span className="font-mono text-[15px] font-semibold tracking-tight">{rack.id}</span>
        <span className={cx('flex items-center gap-1 rounded-md bg-black/25 px-1.5 py-0.5 text-[11px] font-semibold', cls.replace('text-fg-soft/70', 'text-white/80'))} title={`Temperature ${label}${reading?.trendCPerMin != null ? `, ${Math.abs(reading.trendCPerMin).toFixed(1)} °C per minute` : ''}`}>
          <Icon className="h-3.5 w-3.5" aria-hidden />
          {reading?.trendCPerMin != null ? Math.abs(reading.trendCPerMin).toFixed(1) : ''}
        </span>
      </div>
      <div>
        <span className="num text-[30px] font-semibold leading-none tracking-tight">
          {fmtTemp(temp)}
          <span className="ml-0.5 text-base font-medium text-white/70">°C</span>
        </span>
        {forecast != null && (
          <p className="num mt-1 text-xs text-white/80" title="Forecast 60 seconds ahead">
            60 s forecast <span className="font-semibold text-white">{fmtTemp(forecast)}°</span>
          </p>
        )}
      </div>
      <div>
        <div className="mb-1 flex items-center justify-between text-[11px] font-medium text-white/80">
          <span>CPU</span>
          <span className="num">{cpu != null ? `${Math.round(cpu)}%` : '—'}</span>
        </div>
        <div className="h-1.5 overflow-hidden rounded-full bg-black/35" role="presentation">
          <div className="h-full rounded-full bg-white/85 transition-[width] duration-500" style={{ width: `${Math.min(100, Math.max(0, cpu || 0))}%` }} />
        </div>
      </div>
      {(stale || maintenance) && (
        <span className="absolute bottom-2 right-2 rounded bg-black/55 px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide">{stale ? 'Stale' : 'Maint.'}</span>
      )}
    </Link>
  );
}

function ZoneLabel({ zone, unit, unitsFailed }) {
  const fault = unit?.health === 'FAULT';
  return (
    <div className="flex flex-col justify-center gap-1.5 pr-3">
      <p className="text-[15px] font-semibold text-fg">{zone.id}</p>
      <p className="text-xs text-fg-mute">{zone.name}</p>
      {unit ? (
        fault ? (
          <Tag tone="crit">Unit fault</Tag>
        ) : (
          <p className="num text-sm text-fg-soft">
            <span className="font-semibold text-cyan">{unit.outputPct}%</span> <span className="text-fg-mute">{unit.mode === 'AUTO' ? 'cooling' : unit.mode.toLowerCase()}</span>
          </p>
        )
      ) : unitsFailed ? (
        <p className="text-xs text-fg-faint">cooling n/a</p>
      ) : (
        <Skeleton className="h-4 w-14" />
      )}
    </div>
  );
}

export function Legend() {
  return (
    <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-xs text-fg-mute" aria-label="Heat map legend">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <span className="font-semibold uppercase tracking-wide text-fg-soft">Fill · temperature</span>
        {SCALE.map((b) => (
          <span key={b.id} className="inline-flex items-center gap-1.5">
            <span className="h-3 w-3 rounded-[4px]" style={{ background: b.fill }} aria-hidden />
            {b.label}
          </span>
        ))}
        <span className="inline-flex items-center gap-1.5">
          <span className="h-3 w-3 rounded-[4px]" style={{ background: STALE_FILL }} aria-hidden /> Stale sensor
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span className="hatch h-3 w-3 rounded-[4px] bg-ink-500" aria-hidden /> Maintenance
        </span>
      </div>
      <div className="flex items-center gap-3">
        <span className="font-semibold uppercase tracking-wide text-fg-soft">Outline · 60 s forecast</span>
        {[['WATCH', '#fbbf24'], ['WARNING', '#fb923c'], ['CRITICAL', '#f87171']].map(([l, c]) => (
          <span key={l} className="inline-flex items-center gap-1.5">
            <span className="h-3 w-3 rounded-[4px] bg-ink-700" style={{ boxShadow: `0 0 0 2px ${c}` }} aria-hidden />
            {l.charAt(0) + l.slice(1).toLowerCase()}
          </span>
        ))}
      </div>
    </div>
  );
}

export function RackHeatMap() {
  const racks = useRacks();
  const units = useCoolingUnits();
  const topo = useTopology();
  const flashing = useFlashing();

  const byRack = useMemo(() => Object.fromEntries((racks.data || []).map((r) => [r.rackId, r])), [racks.data]);
  const unitByZone = useMemo(() => Object.fromEntries((units.data || []).map((u) => [u.zoneId, u])), [units.data]);
  const zones = useMemo(() => [...(topo.data || [])].sort((a, b) => a.rowIndex - b.rowIndex), [topo.data]);

  const topoLoading = topo.isLoading;

  return (
    <Panel
      title="Rack heat map"
      subtitle="Fill is the current temperature, the outline is the 60 s forecast risk. Select a rack for detail."
      sources={[racks]}
      skeleton={<HeatMapSkeleton />}
      actions={<Thermometer className="h-4 w-4 text-fg-faint" aria-hidden />}
    >
      {topoLoading ? (
        <HeatMapSkeleton />
      ) : topo.isError && !zones.length ? (
        <p className="py-8 text-center text-sm text-warn" role="alert">
          Topology could not be loaded. The rack grid needs it to place each rack.
        </p>
      ) : (
        <>
          <div className="space-y-3">
            {zones.map((zone) => (
              <div key={zone.id} className="grid grid-cols-[104px_1fr] items-stretch" role="group" aria-label={`${zone.name} racks`}>
                <ZoneLabel zone={zone} unit={unitByZone[zone.id]} unitsFailed={units.isError} />
                <div className="grid grid-cols-4 gap-3">
                  {[...zone.racks]
                    .sort((a, b) => a.colIndex - b.colIndex)
                    .map((rack) => (
                      <RackCell key={rack.id} rack={rack} reading={byRack[rack.id]} flash={flashing.has(rack.id)} />
                    ))}
                </div>
              </div>
            ))}
          </div>
          <div className="mt-5 border-t border-ink-600/70 pt-4">
            <Legend />
          </div>
        </>
      )}
    </Panel>
  );
}

function HeatMapSkeleton() {
  return (
    <div className="space-y-3" aria-busy="true" aria-label="Loading heat map">
      {Array.from({ length: 4 }).map((_, r) => (
        <div key={r} className="grid grid-cols-[104px_1fr] gap-0">
          <Skeleton className="mr-3 h-16 self-center" />
          <div className="grid grid-cols-4 gap-3">
            {Array.from({ length: 4 }).map((__, c) => (
              <Skeleton key={c} className="h-[128px] rounded-xl" />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

export { DefaultSkeleton };
