import { useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Area, CartesianGrid, ComposedChart, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Fan, SlidersHorizontal } from 'lucide-react';
import { useCoolingActions, useCoolingSummary, useCoolingUnits } from '../api/sources';
import { errorMessage } from '../api/client';
import { setCoolingMode } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { ZoneCard } from '../components/CoolingZoneCards';
import { Panel } from '../components/Panel';
import { AXIS, ChartFrame, ChartTooltip, GRID, ZONE_COLOR } from '../components/charts';
import { useToast } from '../components/Toast';
import { Button, EmptyState, Skeleton, Tag } from '../components/ui';
import { buildOutputSeries } from '../lib/coolingSeries';
import { fmtClock, fmtKw } from '../lib/format';
import { hasRole } from '../lib/roles';

const tick = (t) => fmtClock(new Date(t).toISOString(), false);
const full = (t) => fmtClock(new Date(t).toISOString());

function ManualOverride({ unit }) {
  const toast = useToast();
  const qc = useQueryClient();
  const [output, setOutput] = useState(unit.outputPct);
  const [busy, setBusy] = useState(false);
  const manual = unit.mode === 'MANUAL';

  const apply = async (mode) => {
    setBusy(true);
    try {
      await setCoolingMode(unit.zoneId, mode, mode === 'MANUAL' ? Number(output) : undefined);
      toast.success(mode === 'MANUAL' ? `${unit.zoneId} held at ${output}%` : `${unit.zoneId} back on automatic control`);
      qc.invalidateQueries({ queryKey: ['cooling-units'] });
    } catch (err) {
      toast.error(`Could not change ${unit.zoneId}`, errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="rounded-xl border border-ink-600 bg-ink-800/60 p-4">
      <div className="mb-3 flex items-center justify-between">
        <p className="font-semibold text-fg">{unit.zoneId}</p>
        <Tag tone={manual ? 'warn' : 'cyan'}>{unit.mode}</Tag>
      </div>
      <label htmlFor={`mo-${unit.zoneId}`} className="mb-1 flex items-center justify-between text-sm text-fg-soft">
        Fixed output <span className="num font-semibold text-fg">{output}%</span>
      </label>
      <input id={`mo-${unit.zoneId}`} type="range" min={0} max={100} step={5} value={output} onChange={(e) => setOutput(e.target.value)} className="w-full accent-cyan" />
      <div className="mt-3 flex gap-2">
        <Button size="sm" variant="warn" disabled={busy} onClick={() => apply('MANUAL')}>Hold this output</Button>
        <Button size="sm" variant="ghost" disabled={busy || !manual} onClick={() => apply('AUTO')}>Return to auto</Button>
      </div>
    </div>
  );
}

export default function Cooling() {
  const { role } = useAuth();
  const units = useCoolingUnits();
  const summary = useCoolingSummary();
  const actions = useCoolingActions(500);

  const zoneIds = useMemo(() => [...(units.data || [])].map((u) => u.zoneId).sort(), [units.data]);
  const series = useMemo(() => buildOutputSeries(actions.data || [], zoneIds), [actions.data, zoneIds]);
  const power = series.rows.filter((r) => r.totalKw != null);
  const latestActions = useMemo(() => (actions.data || []).slice(0, 40), [actions.data]);
  const sortedUnits = useMemo(() => [...(units.data || [])].sort((a, b) => a.zoneId.localeCompare(b.zoneId)), [units.data]);

  return (
    <div className="space-y-5">
      <Panel title="Zones" subtitle="Current output, mode and health of each cooling unit" sources={[units]} skeleton={<Skeleton className="h-[200px]" />}>
        <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
          {sortedUnits.map((u) => (
            <ZoneCard key={u.zoneId} unit={u} />
          ))}
        </div>
      </Panel>

      <div className="grid gap-5 xl:grid-cols-2">
        <Panel title="Output per zone over time" subtitle={series.rows.length ? `${series.rows.length} controller changes, ${fmtClock(new Date(series.rows[0].t).toISOString())} to ${fmtClock(new Date(series.rows.at(-1).t).toISOString())}` : 'From the controller action log'} sources={[actions]} skeleton={<Skeleton className="h-[320px]" />}>
          {series.rows.length === 0 ? (
            <EmptyState icon={Fan} title="No changes logged yet" body="Each change the controller makes to a zone's output is recorded and drawn here." />
          ) : (
            <ChartFrame height={320} label="Cooling output percent per zone over time">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={series.rows} margin={{ top: 8, right: 12, left: -8, bottom: 0 }}>
                  <CartesianGrid {...GRID} />
                  <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']} tickFormatter={tick} {...AXIS} minTickGap={56} />
                  <YAxis {...AXIS} domain={[0, 100]} unit="%" width={52} />
                  <Tooltip content={<ChartTooltip digits={0} unit="%" labelFormatter={full} />} />
                  <Legend wrapperStyle={{ fontSize: 12, color: '#b4c0d8' }} iconType="plainline" />
                  {series.zones.map((z) => (
                    <Line key={z} type="stepAfter" dataKey={z} name={z} stroke={ZONE_COLOR[z] || '#22d3ee'} strokeWidth={2.25} dot={false} connectNulls isAnimationActive={false} />
                  ))}
                </LineChart>
              </ResponsiveContainer>
            </ChartFrame>
          )}
        </Panel>

        <Panel
          title="Modelled cooling power vs static baseline"
          subtitle="coolingKw = 0.5 + 4.0 × (output / 100)³ per zone. A model, not a measurement."
          sources={[actions, summary]}
          skeleton={<Skeleton className="h-[320px]" />}
        >
          {power.length === 0 ? (
            <EmptyState title="Not enough data" body="Power is drawn once every zone has a logged output." />
          ) : (
            <>
              <ChartFrame height={290} label="Modelled cooling power against the static 70 percent baseline">
                <ResponsiveContainer width="100%" height="100%">
                  <ComposedChart data={power} margin={{ top: 8, right: 12, left: -4, bottom: 0 }}>
                    <CartesianGrid {...GRID} />
                    <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']} tickFormatter={tick} {...AXIS} minTickGap={56} />
                    <YAxis {...AXIS} domain={[0, 'auto']} unit=" kW" width={60} />
                    <Tooltip content={<ChartTooltip digits={2} unit=" kW" labelFormatter={full} />} />
                    <Legend wrapperStyle={{ fontSize: 12, color: '#b4c0d8' }} iconType="plainline" />
                    <Area type="stepAfter" dataKey="totalKw" name="ThermoGrid (modelled)" stroke="#2dd4bf" fill="#2dd4bf" fillOpacity={0.18} strokeWidth={2.25} dot={false} isAnimationActive={false} />
                    <Line type="monotone" dataKey="baselineKw" name="Static 70% policy" stroke="#fb923c" strokeWidth={2} strokeDasharray="6 4" dot={false} isAnimationActive={false} />
                  </ComposedChart>
                </ResponsiveContainer>
              </ChartFrame>
              {summary.hasData && (
                <p className="num mt-3 text-sm text-fg-soft">
                  Now <span className="font-semibold text-fg">{fmtKw(summary.data.totalPowerKw)}</span> vs <span className="font-semibold text-fg">{fmtKw(summary.data.baselineTotalKw)}</span> baseline ·{' '}
                  <span className="font-semibold text-teal">{Number(summary.data.savingPct).toFixed(0)}% saving</span>
                  <span className="text-fg-mute"> · {Number(summary.data.modelledEnergySavedKwh).toFixed(3)} kWh saved since the controller started, modelled</span>
                </p>
              )}
            </>
          )}
        </Panel>
      </div>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
        <Panel title="Action log" subtitle="Every output change with its reason" sources={[actions]} skeleton={<Skeleton className="h-[320px]" />} flush>
          {latestActions.length === 0 ? (
            <EmptyState title="No actions yet" />
          ) : (
            <div className="max-h-[440px] overflow-auto">
              <table className="w-full text-[15px]">
                <thead className="sticky top-0 bg-ink-850 text-xs uppercase tracking-wide text-fg-mute shadow-[0_1px_0_0_var(--color-ink-600)]">
                  <tr>
                    <th className="px-5 py-2.5 text-left font-semibold">Time</th>
                    <th className="px-3 py-2.5 text-left font-semibold">Zone</th>
                    <th className="px-3 py-2.5 text-left font-semibold">Change</th>
                    <th className="px-3 py-2.5 text-left font-semibold">Mode</th>
                    <th className="px-5 py-2.5 text-left font-semibold">Reason</th>
                  </tr>
                </thead>
                <tbody className="num divide-y divide-ink-600/60">
                  {latestActions.map((a) => (
                    <tr key={a.id} className="hover:bg-ink-800/70">
                      <td className="px-5 py-2 text-fg-mute">{fmtClock(a.createdAt)}</td>
                      <td className="px-3 py-2 font-semibold text-fg">{a.zoneId}</td>
                      <td className="px-3 py-2 text-fg-soft">
                        {a.oldOutputPct} → <span className={a.newOutputPct > a.oldOutputPct ? 'font-semibold text-orange' : 'font-semibold text-sky'}>{a.newOutputPct}%</span>
                      </td>
                      <td className="px-3 py-2"><Tag tone={a.mode === 'AUTO' ? 'neutral' : a.mode === 'MANUAL' ? 'warn' : 'crit'}>{a.mode}</Tag></td>
                      <td className="px-5 py-2 font-sans text-sm text-fg-soft">{a.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>

        {hasRole(role, 'OPERATOR') && (
          <Panel title="Manual override" subtitle="Hold a fixed output. Stale data still triggers fail-safe." sources={[units]} skeleton={<Skeleton className="h-[320px]" />} actions={<SlidersHorizontal className="h-4 w-4 text-fg-faint" aria-hidden />}>
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-1 2xl:grid-cols-2">
              {sortedUnits.map((u) => (
                <ManualOverride key={`${u.zoneId}-${u.mode}`} unit={u} />
              ))}
            </div>
          </Panel>
        )}
      </div>
    </div>
  );
}
