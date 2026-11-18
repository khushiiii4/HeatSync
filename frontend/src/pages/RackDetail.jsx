import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Area, CartesianGrid, ComposedChart, Line, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { ArrowLeft, ArrowDown, ArrowUp, HelpCircle, Minus, SearchX } from 'lucide-react';
import { useAlerts, useRackHistory, useRacks, useTopology, useWorkloads } from '../api/sources';
import { PlacementExplainer } from '../components/PlacementExplainer';
import { Panel } from '../components/Panel';
import { AXIS, ChartFrame, ChartTooltip, GRID } from '../components/charts';
import { EmptyState, Skeleton, Tag, levelTone } from '../components/ui';
import { bandFor, trendDirection } from '../lib/colorScale';
import { fmtClock, fmtDateTime, fmtKw, fmtTemp } from '../lib/format';

const PRIORITY_TONE = { LOW: 'blue', MEDIUM: 'cyan', HIGH: 'warn' };

function Stat({ label, value, sub, accent }) {
  return (
    <div className="min-w-[120px]">
      <p className="text-xs font-semibold uppercase tracking-[0.12em] text-fg-mute">{label}</p>
      <p className="num mt-1 text-[30px] font-semibold leading-none tracking-tight" style={accent ? { color: accent } : undefined}>
        {value}
      </p>
      {sub && <p className="mt-1.5 text-sm text-fg-mute">{sub}</p>}
    </div>
  );
}

export default function RackDetail() {
  const { rackId } = useParams();
  const racks = useRacks();
  const topo = useTopology();
  const history = useRackHistory(rackId, 10);
  const workloads = useWorkloads({ rackId });
  const alerts = useAlerts('ALL', 200);
  const [explain, setExplain] = useState(null);

  const rack = (topo.data || []).flatMap((z) => z.racks.map((r) => ({ ...r, zoneName: z.name }))).find((r) => r.id === rackId);
  const reading = (racks.data || []).find((r) => r.rackId === rackId);
  const unknownRack = topo.data && !rack;

  const points = useMemo(() => (history.data?.points || []).map((p) => ({ ...p, t: Date.parse(p.recordedAt) })), [history.data]);
  const rackAlerts = useMemo(() => (alerts.data || []).filter((a) => a.rackId === rackId), [alerts.data, rackId]);
  const running = (workloads.data || []).filter((w) => w.status === 'RUNNING');
  const cpuUsed = running.reduce((s, w) => s + w.cpuDemand, 0);

  if (unknownRack) {
    return (
      <div className="card-surface">
        <EmptyState icon={SearchX} title={`No rack called ${rackId}`} body="Racks are R11 to R44: the first digit is the row and zone, the second the column.">
          <Link to="/" className="mt-2 text-sm font-medium text-cyan">
            Back to the heat map
          </Link>
        </EmptyState>
      </div>
    );
  }

  const band = reading ? bandFor(reading.tempC) : null;
  const dir = trendDirection(reading?.trendCPerMin);
  const TrendIcon = { up: ArrowUp, down: ArrowDown, flat: Minus }[dir];
  const stale = reading?.sensorStatus === 'STALE';

  return (
    <div className="space-y-5">
      <Link to="/" className="inline-flex items-center gap-2 text-sm font-medium text-fg-mute transition hover:text-cyan">
        <ArrowLeft className="h-4 w-4" aria-hidden /> Overview
      </Link>

      <Panel title={`Rack ${rackId}`} subtitle={rack ? `${rack.zoneName} · row ${rack.rowIndex}, column ${rack.colIndex}` : undefined} sources={[racks]} skeleton={<Skeleton className="h-24" />}
        actions={
          <>
            {rack?.status === 'MAINTENANCE' && <Tag tone="warn">Maintenance</Tag>}
            {stale && <Tag tone="neutral">Sensor stale</Tag>}
            {reading && <Tag tone={levelTone(reading.riskLevel)}>{reading.riskLevel}</Tag>}
          </>
        }
      >
        {reading ? (
          <div className="flex flex-wrap items-end gap-x-12 gap-y-5">
            <Stat label="Temperature" value={`${fmtTemp(reading.tempC)} °C`} accent={band?.soft} sub={`updated ${fmtClock(reading.updatedAt)}`} />
            <Stat label="Forecast, 60 s" value={`${fmtTemp(reading.forecast60C)} °C`} sub={reading.forecast60C > reading.tempC ? 'above current' : 'at or below current'} />
            <div>
              <p className="text-xs font-semibold uppercase tracking-[0.12em] text-fg-mute">Trend</p>
              <p className="num mt-1 flex items-center gap-1.5 text-[30px] font-semibold leading-none">
                <TrendIcon className="h-6 w-6 text-fg-soft" aria-hidden />
                {Math.abs(reading.trendCPerMin ?? 0).toFixed(1)}
                <span className="text-base font-medium text-fg-mute">°C/min</span>
              </p>
              <p className="mt-1.5 text-sm text-fg-mute">{{ up: 'rising', down: 'falling', flat: 'steady' }[dir]}</p>
            </div>
            <Stat label="Power" value={fmtKw(reading.powerKw)} />
            <Stat label="CPU load" value={`${Math.round(reading.cpuLoadPct)}%`} sub={`${cpuUsed} of ${rack?.cpuCapacity ?? 100} units assigned`} />
          </div>
        ) : (
          <p className="py-4 text-sm text-fg-mute">This rack has not reported a reading yet.</p>
        )}
      </Panel>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)]">
        <Panel title="Temperature and 60 s forecast" subtitle="Last 10 minutes" sources={[history]} skeleton={<Skeleton className="h-[300px]" />}>
          {points.length === 0 ? (
            <EmptyState title="No history yet" body="Readings arrive every two seconds once the simulator is running." />
          ) : (
            <ChartFrame height={300} label="Temperature and forecast over the last ten minutes">
              <ResponsiveContainer width="100%" height="100%">
                <ComposedChart data={points} margin={{ top: 8, right: 12, left: -8, bottom: 0 }}>
                  <CartesianGrid {...GRID} />
                  <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']} tickFormatter={(t) => fmtClock(new Date(t).toISOString(), false)} {...AXIS} minTickGap={48} />
                  <YAxis {...AXIS} domain={['auto', 'auto']} unit=" °C" width={64} />
                  <Tooltip content={<ChartTooltip digits={1} unit=" °C" labelFormatter={(t) => fmtClock(new Date(t).toISOString())} />} />
                  <ReferenceLine y={30} stroke="#fbbf24" strokeDasharray="4 4" label={{ value: 'WATCH 30', fill: '#fbbf24', fontSize: 11, position: 'insideBottomRight' }} />
                  <ReferenceLine y={34} stroke="#fb923c" strokeDasharray="4 4" label={{ value: 'WARNING 34', fill: '#fb923c', fontSize: 11, position: 'insideBottomRight' }} />
                  <ReferenceLine y={38} stroke="#f87171" strokeDasharray="4 4" label={{ value: 'CRITICAL 38', fill: '#f87171', fontSize: 11, position: 'insideBottomRight' }} />
                  <Line type="monotone" dataKey="forecast60C" name="Forecast (60 s ahead)" stroke="#a78bfa" strokeWidth={2} strokeDasharray="6 4" dot={false} isAnimationActive={false} />
                  <Line type="monotone" dataKey="tempC" name="Temperature" stroke="#22d3ee" strokeWidth={2.5} dot={false} isAnimationActive={false} />
                </ComposedChart>
              </ResponsiveContainer>
            </ChartFrame>
          )}
          <div className="mt-3 flex items-center gap-5 text-xs text-fg-mute">
            <span className="flex items-center gap-2"><span className="h-0.5 w-5 bg-cyan" aria-hidden /> Temperature</span>
            <span className="flex items-center gap-2"><span className="h-0.5 w-5 border-t-2 border-dashed border-[#a78bfa]" aria-hidden /> Forecast, 60 s ahead</span>
          </div>
        </Panel>

        <Panel title="Power and CPU load" subtitle="Last 10 minutes" sources={[history]} skeleton={<Skeleton className="h-[300px]" />}>
          {points.length === 0 ? (
            <EmptyState title="No history yet" />
          ) : (
            <ChartFrame height={300} label="Power in kilowatts and CPU load percent over the last ten minutes">
              <ResponsiveContainer width="100%" height="100%">
                <ComposedChart data={points} margin={{ top: 8, right: 4, left: -8, bottom: 0 }}>
                  <CartesianGrid {...GRID} />
                  <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']} tickFormatter={(t) => fmtClock(new Date(t).toISOString(), false)} {...AXIS} minTickGap={48} />
                  <YAxis yAxisId="kw" {...AXIS} domain={[0, 10]} unit=" kW" width={58} />
                  <YAxis yAxisId="cpu" orientation="right" {...AXIS} domain={[0, 100]} unit="%" width={46} />
                  <Tooltip content={<ChartTooltip digits={1} labelFormatter={(t) => fmtClock(new Date(t).toISOString())} />} />
                  <Area yAxisId="kw" type="monotone" dataKey="powerKw" name="Power (kW)" stroke="#2dd4bf" fill="#2dd4bf" fillOpacity={0.15} strokeWidth={2} dot={false} isAnimationActive={false} />
                  <Line yAxisId="cpu" type="stepAfter" dataKey="cpuLoadPct" name="CPU load (%)" stroke="#3b82f6" strokeWidth={2} dot={false} isAnimationActive={false} />
                </ComposedChart>
              </ResponsiveContainer>
            </ChartFrame>
          )}
          <div className="mt-3 flex items-center gap-5 text-xs text-fg-mute">
            <span className="flex items-center gap-2"><span className="h-2.5 w-2.5 rounded-sm bg-teal/60" aria-hidden /> Power (kW, left)</span>
            <span className="flex items-center gap-2"><span className="h-0.5 w-5 bg-azure" aria-hidden /> CPU load (%, right)</span>
          </div>
        </Panel>
      </div>

      <div className="grid gap-5 xl:grid-cols-2">
        <Panel title="Workloads on this rack" subtitle={`${running.length} running · ${cpuUsed} CPU units`} sources={[workloads]} skeleton={<Skeleton className="h-32" />}>
          {running.length === 0 ? (
            <EmptyState title="Nothing running here" body="No workload is assigned to this rack." />
          ) : (
            <ul className="divide-y divide-ink-600/70">
              {running.map((w) => (
                <li key={w.id} className="flex items-center justify-between gap-3 py-2.5">
                  <div className="min-w-0">
                    <p className="truncate text-[15px] font-medium text-fg">{w.name}</p>
                    <p className="num text-xs text-fg-mute">#{w.id} · {w.cpuDemand} CPU units</p>
                  </div>
                  <div className="flex items-center gap-2">
                    <Tag tone={PRIORITY_TONE[w.priority]}>{w.priority}</Tag>
                    <button type="button" onClick={() => setExplain(w)} className="rounded-md p-1.5 text-fg-mute transition hover:bg-ink-700 hover:text-cyan" aria-label={`Why is ${w.name} on ${rackId}?`} title="Why this rack?">
                      <HelpCircle className="h-4 w-4" />
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel title="Alerts for this rack" subtitle="Newest first" sources={[alerts]} skeleton={<Skeleton className="h-32" />}>
          {rackAlerts.length === 0 ? (
            <EmptyState title="No alerts recorded" body="This rack has not crossed a threshold in the retained history." />
          ) : (
            <ul className="max-h-[320px] divide-y divide-ink-600/70 overflow-y-auto pr-1">
              {rackAlerts.map((a) => (
                <li key={a.id} className="flex items-center justify-between gap-3 py-2.5">
                  <div>
                    <div className="flex items-center gap-2">
                      <Tag tone={levelTone(a.level)}>{a.level}</Tag>
                      <Tag tone={a.basis === 'PREDICTED' ? 'blue' : 'neutral'}>{a.basis}</Tag>
                      <Tag tone={a.state === 'ACTIVE' ? 'orange' : 'ok'}>{a.state}</Tag>
                    </div>
                    <p className="num mt-1 text-sm text-fg-soft">{fmtTemp(a.tempC)} °C now · forecast {fmtTemp(a.forecastC)} °C</p>
                  </div>
                  <div className="num text-right text-xs text-fg-mute">
                    <p>raised {fmtDateTime(a.raisedAt)}</p>
                    {a.clearedAt && <p>cleared {fmtDateTime(a.clearedAt)}</p>}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>
      <PlacementExplainer workloadId={explain?.id} workloadName={explain?.name} onClose={() => setExplain(null)} />
    </div>
  );
}
