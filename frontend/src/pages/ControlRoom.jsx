import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Flame, Gauge, Pause, Play, Power, RotateCcw, Sprout, Thermometer, Wind, ZapOff } from 'lucide-react';
import { useFailures, useSchedulerStatus, useSimState } from '../api/sources';
import { errorMessage } from '../api/client';
import { clearFailure, injectFailure, rebalanceNow, seedDemo, setAutopilot, simControl } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { Panel } from '../components/Panel';
import { useToast } from '../components/Toast';
import { Button, ConfirmDialog, EmptyState, Skeleton, Tag, cx, inputCls } from '../components/ui';
import { fmtClock } from '../lib/format';
import { hasRole } from '../lib/roles';

const FAILURE_TYPES = {
  COOLING_DEGRADED: { label: 'Degrade cooling', target: 'zone', param: { label: 'Remaining efficiency (0–1)', def: 0.15, min: 0.05, max: 0.95, step: 0.05 }, icon: Wind, tone: 'warn',
    effect: 'The zone keeps only a fraction of its cooling effectiveness. Racks warm, telemetry raises a PREDICTED alert, the controller pushes output towards 100% and the scheduler moves low-priority work away.' },
  COOLING_FAILURE: { label: 'Fail cooling unit', target: 'zone', icon: ZapOff, tone: 'crit',
    effect: 'The unit stops cooling entirely. After two readings the controller marks it FAULT, the other zones raise their floor to 60% and the scheduler drains the zone, low priority first.' },
  SENSOR_DROPOUT: { label: 'Sensor dropout', target: 'rack', icon: Thermometer, tone: 'warn',
    effect: 'The rack stops reporting. After about 10 seconds it turns STALE (grey), its forecast freezes and the scheduler places no new work on it.' },
  HEAT_SPIKE: { label: 'Heat spike', target: 'rack', param: { label: 'Extra heat (kW)', def: 6, min: 1, max: 20, step: 1 }, icon: Flame, tone: 'crit',
    effect: 'The rack gets extra heat load for the chosen time. Expect a forecast alert, a cooling boost and a migration off the rack.' },
};

const ZONES = ['Z1', 'Z2', 'Z3', 'Z4'];
const RACKS = ZONES.flatMap((z, zi) => [1, 2, 3, 4].map((c) => `R${zi + 1}${c}`));

const PRESETS = [
  { id: 'degrade-z2', scenario: 'Scenarios 2 – 5', title: 'Degrade cooling in Z2', body: 'Z2 racks warm; watch the forecast alert, the cooling climb and the migrations. Clear it to see the boost released.', req: { type: 'COOLING_DEGRADED', targetId: 'Z2', param: 0.15, durationSec: 0 } },
  { id: 'fail-z3', scenario: 'Scenario 6', title: 'Fail the cooling unit in Z3', body: 'Fault detection, other zones lift their floor to 60%, Z3 is drained and refused as a destination.', req: { type: 'COOLING_FAILURE', targetId: 'Z3', durationSec: 0 } },
  { id: 'drop-r23', scenario: 'Stale sensor', title: 'Drop the sensor on R23', body: 'R23 turns stale and grey within about 10 seconds and receives no new work.', req: { type: 'SENSOR_DROPOUT', targetId: 'R23', durationSec: 0 } },
];

function Switch({ checked, onChange, disabled, label }) {
  return (
    <button type="button" role="switch" aria-checked={checked} aria-label={label} disabled={disabled} onClick={() => onChange(!checked)} className={cx('relative h-7 w-12 shrink-0 rounded-full border transition-colors duration-200 disabled:cursor-not-allowed disabled:opacity-50', checked ? 'border-cyan/60 bg-cyan/25' : 'border-ink-500 bg-ink-700')}>
      <span className={cx('absolute top-0.5 h-5.5 w-5.5 rounded-full shadow transition-all duration-200', checked ? 'left-[22px] bg-cyan' : 'left-0.5 bg-fg-mute')} style={{ height: 22, width: 22 }} />
    </button>
  );
}

export default function ControlRoom() {
  const { role } = useAuth();
  const isAdmin = hasRole(role, 'ADMIN');
  const toast = useToast();
  const qc = useQueryClient();
  const failures = useFailures();
  const sched = useSchedulerStatus();
  const sim = useSimState();

  const [pending, setPending] = useState(null); // { title, body, confirmLabel, tone, run }
  const [busy, setBusy] = useState(false);
  const [custom, setCustom] = useState({ type: 'COOLING_DEGRADED', targetId: 'Z2', param: 0.15, durationSec: 0 });

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['failures'] });
    qc.invalidateQueries({ queryKey: ['scheduler-status'] });
    qc.invalidateQueries({ queryKey: ['sim-state'] });
  };

  const execute = async () => {
    setBusy(true);
    try {
      await pending.run();
      refresh();
      setPending(null);
    } catch (err) {
      toast.error(pending.failTitle || 'Action failed', errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const askInject = (req, label) => {
    const meta = FAILURE_TYPES[req.type];
    setPending({
      title: `Inject: ${label}?`,
      confirmLabel: 'Inject failure',
      tone: 'danger',
      failTitle: 'Could not inject the failure',
      body: (
        <>
          <p><span className="font-semibold text-fg">{meta.label}</span> on <span className="font-mono font-semibold text-fg">{req.targetId}</span>{req.durationSec ? ` for ${req.durationSec} s` : ' until you clear it'}.</p>
          <p>{meta.effect}</p>
        </>
      ),
      run: async () => {
        const f = await injectFailure(req);
        toast.warn(`${meta.label} injected on ${f.targetId}`, f.endsAt ? 'It ends automatically.' : 'It stays active until cleared below.');
      },
    });
  };

  const clearOne = async (f) => {
    try {
      await clearFailure(f.id);
      toast.success(`Cleared ${f.type.replace('_', ' ').toLowerCase()} on ${f.targetId}`);
      refresh();
    } catch (err) {
      toast.error('Could not clear the failure', errorMessage(err));
    }
  };

  const toggleAutopilot = async (enabled) => {
    try {
      await setAutopilot(enabled);
      toast.success(`Autopilot ${enabled ? 'on' : 'off'}`, enabled ? 'The scheduler will migrate work automatically again.' : 'Thermal migration and draining are paused. Cooling still reacts.');
      refresh();
    } catch (err) {
      toast.error('Could not change the autopilot', errorMessage(err));
    }
  };

  const active = failures.data || [];
  const meta = FAILURE_TYPES[custom.type];
  const targets = meta.target === 'zone' ? ZONES : RACKS;
  const paused = sim.data?.paused;

  const askResetDemo = () =>
    setPending({
      title: 'Reset the demo?',
      confirmLabel: 'Reset demo',
      tone: 'danger',
      failTitle: 'Reset did not complete',
      body: (
        <>
          <p>This clears every active failure, resets the simulated plant to its starting temperatures and re-runs the demo seed.</p>
          <p>Existing workloads are kept; the seed only adds the demo set if it has never run.</p>
        </>
      ),
      run: async () => {
        for (const f of active) await clearFailure(f.id);
        await simControl('RESET');
        const seeded = await seedDemo();
        toast.success('Demo reset', seeded.created ? `Seeded ${seeded.workloadCount} workloads.` : `${seeded.workloadCount} demo workloads already present.`);
      },
    });

  return (
    <div className="space-y-5">
      <div className="grid gap-5 xl:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)]">
        <Panel title="Scenario presets" subtitle="One click to reproduce a demonstration scenario from the plan" sources={[]}>
          <div className="grid gap-3 md:grid-cols-3">
            {PRESETS.map((p) => {
              const Icon = FAILURE_TYPES[p.req.type].icon;
              return (
                <div key={p.id} className="flex flex-col rounded-xl border border-ink-600 bg-ink-800/60 p-4">
                  <div className="mb-3 flex items-center justify-between">
                    <Tag tone={FAILURE_TYPES[p.req.type].tone}>{p.scenario}</Tag>
                    <Icon className="h-5 w-5 text-fg-mute" aria-hidden />
                  </div>
                  <p className="text-[15px] font-semibold text-fg">{p.title}</p>
                  <p className="mt-1.5 flex-1 text-sm leading-snug text-fg-mute">{p.body}</p>
                  <Button className="mt-4" variant={p.req.type === 'COOLING_FAILURE' ? 'danger' : 'warn'} onClick={() => askInject(p.req, p.title)}>
                    Review and inject
                  </Button>
                </div>
              );
            })}
          </div>
        </Panel>

        <Panel title="Autopilot" subtitle="Automatic migration, drain and rebalance" sources={[sched]} skeleton={<Skeleton className="h-28" />}>
          <div className="flex items-center justify-between gap-4">
            <div>
              <p className="text-[15px] font-medium text-fg">
                Scheduler autopilot is <span className={sched.data?.autopilot ? 'text-cyan' : 'text-warn'}>{sched.data?.autopilot ? 'on' : 'off'}</span>
              </p>
              <p className="mt-1 text-sm text-fg-mute">Turn it off to show unmanaged heating, then on to show the system deciding.</p>
            </div>
            <Switch checked={!!sched.data?.autopilot} onChange={toggleAutopilot} label="Autopilot" disabled={!sched.hasData} />
          </div>
          <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-ink-600/70 pt-4">
            <p className="text-sm text-fg-mute">
              Mode <Tag tone={sched.data?.mode === 'HOLD' ? 'orange' : 'ok'}>{sched.data?.mode || '—'}</Tag>
            </p>
            <Button
              size="sm"
              variant="secondary"
              disabled={!sched.hasData}
              onClick={async () => {
                try {
                  const r = await rebalanceNow();
                  toast.info('Rebalance run', r.message || `${r.moved} workload(s) moved`);
                } catch (err) {
                  toast.error('Rebalance failed', errorMessage(err));
                }
              }}
            >
              <Gauge className="h-4 w-4" aria-hidden /> Rebalance now
            </Button>
          </div>
        </Panel>
      </div>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
        <Panel title="Inject a failure" subtitle="Custom target and size" sources={[]}>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="sm:col-span-2">
              <label htmlFor="f-type" className="mb-1.5 block text-sm font-medium text-fg-soft">Failure type</label>
              <select id="f-type" className={inputCls} value={custom.type} onChange={(e) => {
                const m = FAILURE_TYPES[e.target.value];
                setCustom({ type: e.target.value, targetId: m.target === 'zone' ? 'Z2' : 'R23', param: m.param?.def ?? '', durationSec: e.target.value === 'HEAT_SPIKE' ? 120 : 0 });
              }}>
                {Object.entries(FAILURE_TYPES).map(([k, v]) => (
                  <option key={k} value={k}>{v.label}</option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="f-target" className="mb-1.5 block text-sm font-medium text-fg-soft">{meta.target === 'zone' ? 'Cooling zone' : 'Rack'}</label>
              <select id="f-target" className={inputCls} value={custom.targetId} onChange={(e) => setCustom({ ...custom, targetId: e.target.value })}>
                {targets.map((t) => (
                  <option key={t}>{t}</option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="f-duration" className="mb-1.5 block text-sm font-medium text-fg-soft">Duration (s, 0 = until cleared)</label>
              <input id="f-duration" inputMode="numeric" className={cx(inputCls, 'num')} value={custom.durationSec} onChange={(e) => setCustom({ ...custom, durationSec: e.target.value.replace(/\D/g, '') })} />
            </div>
            {meta.param && (
              <div className="sm:col-span-2">
                <label htmlFor="f-param" className="mb-1.5 flex items-center justify-between text-sm font-medium text-fg-soft">
                  {meta.param.label} <span className="num font-semibold text-fg">{custom.param}</span>
                </label>
                <input id="f-param" type="range" min={meta.param.min} max={meta.param.max} step={meta.param.step} value={custom.param} onChange={(e) => setCustom({ ...custom, param: Number(e.target.value) })} className="w-full accent-cyan" />
              </div>
            )}
          </div>
          <p className="mt-4 rounded-lg border border-ink-600 bg-ink-900/50 p-3 text-sm leading-snug text-fg-mute">{meta.effect}</p>
          <Button className="mt-4 w-full" variant="warn" onClick={() => askInject({ type: custom.type, targetId: custom.targetId, durationSec: Number(custom.durationSec) || 0, ...(meta.param && { param: Number(custom.param) }) }, meta.label)}>
            Review and inject
          </Button>
        </Panel>

        <Panel title="Active failures" subtitle={failures.hasData ? `${active.length} active` : undefined} sources={[failures]} skeleton={<Skeleton className="h-40" />}>
          {active.length === 0 ? (
            <EmptyState icon={Power} title="No failures active" body="The plant is running normally. Inject one from the presets or the form." />
          ) : (
            <ul className="divide-y divide-ink-600/70">
              {active.map((f) => (
                <li key={f.id} className="flex items-center justify-between gap-3 py-3">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <Tag tone={FAILURE_TYPES[f.type]?.tone || 'warn'}>{f.type.replace('_', ' ')}</Tag>
                      <span className="font-mono text-base font-semibold text-fg">{f.targetId}</span>
                      {f.param != null && <span className="num text-xs text-fg-mute">param {f.param}</span>}
                    </div>
                    <p className="num mt-1 text-xs text-fg-mute">
                      since {fmtClock(f.startedAt)} · {f.endsAt ? `ends ${fmtClock(f.endsAt)}` : 'until cleared'} · by {f.injectedBy}
                    </p>
                  </div>
                  <Button size="sm" variant="secondary" onClick={() => clearOne(f)}>Clear</Button>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>

      {isAdmin && (
        <Panel title="Simulation and demo" subtitle="Administrator controls" sources={[sim]} skeleton={<Skeleton className="h-20" />}>
          <div className="flex flex-wrap items-center gap-3">
            <Tag tone={paused ? 'warn' : 'ok'}>{paused ? 'Paused' : 'Running'}</Tag>
            {sim.hasData && <span className="num text-sm text-fg-mute">tick {sim.data.tick}</span>}
            <div className="ml-auto flex flex-wrap gap-2">
              <Button
                variant="secondary"
                onClick={() => setPending({ title: paused ? 'Resume the simulation?' : 'Pause the simulation?', confirmLabel: paused ? 'Resume' : 'Pause', tone: 'danger', failTitle: 'Control failed', body: <p>{paused ? 'The plant resumes advancing every two seconds.' : 'Temperatures stop advancing and no new readings are published until you resume.'}</p>, run: async () => { await simControl(paused ? 'RESUME' : 'PAUSE'); toast.success(paused ? 'Simulation resumed' : 'Simulation paused'); } })}
              >
                {paused ? <Play className="h-4 w-4" aria-hidden /> : <Pause className="h-4 w-4" aria-hidden />} {paused ? 'Resume' : 'Pause'}
              </Button>
              <Button variant="secondary" onClick={async () => { try { const r = await seedDemo(); toast.success(r.created ? `Seeded ${r.workloadCount} demo workloads` : 'Demo workloads already present', `${r.totalCpuUnits} CPU units in total.`); qc.invalidateQueries({ queryKey: ['workloads'] }); qc.invalidateQueries({ queryKey: ['workload-summary'] }); } catch (err) { toast.error('Seeding failed', errorMessage(err)); } }}>
                <Sprout className="h-4 w-4" aria-hidden /> Seed demo workloads
              </Button>
              <Button variant="danger" onClick={askResetDemo}>
                <RotateCcw className="h-4 w-4" aria-hidden /> Reset demo
              </Button>
            </div>
          </div>
        </Panel>
      )}

      <ConfirmDialog open={!!pending} title={pending?.title} body={pending?.body} confirmLabel={pending?.confirmLabel} tone={pending?.tone} busy={busy} onConfirm={execute} onCancel={() => setPending(null)} />
    </div>
  );
}
