import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, Boxes, HelpCircle, Plus, Search, Trash2 } from 'lucide-react';
import { useSchedulerStatus, useTopology, useWorkloadSummary, useWorkloads } from '../api/sources';
import { errorMessage } from '../api/client';
import { removeWorkload, submitWorkload } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { ConfirmDialog, EmptyState, Segmented, Skeleton, Tag, Button, cx, inputCls } from '../components/ui';
import { Panel } from '../components/Panel';
import { PlacementExplainer } from '../components/PlacementExplainer';
import { CpuByRackChart, buildRackStacks } from '../components/WorkloadDistribution';
import { useToast } from '../components/Toast';
import { hasRole } from '../lib/roles';

const PRIORITY_TONE = { LOW: 'blue', MEDIUM: 'cyan', HIGH: 'warn' };
const STATUS_TONE = { RUNNING: 'ok', PENDING: 'warn', REMOVED: 'neutral' };

export function validateSubmission({ name, cpuDemand }) {
  const errors = {};
  if (!name.trim()) errors.name = 'Give the workload a name.';
  else if (name.trim().length > 60) errors.name = 'Use 60 characters or fewer.';
  const cpu = Number(cpuDemand);
  if (cpuDemand === '' || !Number.isInteger(cpu) || cpu < 1 || cpu > 100) errors.cpuDemand = 'Enter a whole number from 1 to 100.';
  return errors;
}

function SubmitForm() {
  const toast = useToast();
  const qc = useQueryClient();
  const [form, setForm] = useState({ name: '', cpuDemand: '20', priority: 'MEDIUM' });
  const [errors, setErrors] = useState({});
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    const found = validateSubmission(form);
    setErrors(found);
    if (Object.keys(found).length) return;
    setBusy(true);
    try {
      const w = await submitWorkload({ name: form.name.trim(), cpuDemand: Number(form.cpuDemand), priority: form.priority });
      toast.success(`Submitted ${w.name}`, `Workload #${w.id} is ${w.status.toLowerCase()}; the scheduler will place it on the best rack.`);
      setForm({ ...form, name: '' });
      qc.invalidateQueries({ queryKey: ['workloads'] });
      qc.invalidateQueries({ queryKey: ['workload-summary'] });
    } catch (err) {
      toast.error('Could not submit the workload', errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} className="grid gap-4 sm:grid-cols-[1.6fr_1fr_1fr_auto] sm:items-start" noValidate>
      <div>
        <label htmlFor="wl-name" className="mb-1.5 block text-sm font-medium text-fg-soft">Name</label>
        <input id="wl-name" className={cx(inputCls, errors.name && 'border-crit')} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="batch-etl-18" aria-invalid={!!errors.name} aria-describedby={errors.name ? 'wl-name-err' : undefined} />
        {errors.name && <p id="wl-name-err" className="mt-1 text-xs text-crit">{errors.name}</p>}
      </div>
      <div>
        <label htmlFor="wl-cpu" className="mb-1.5 block text-sm font-medium text-fg-soft">CPU units</label>
        <input id="wl-cpu" inputMode="numeric" className={cx(inputCls, 'num', errors.cpuDemand && 'border-crit')} value={form.cpuDemand} onChange={(e) => setForm({ ...form, cpuDemand: e.target.value })} aria-invalid={!!errors.cpuDemand} aria-describedby={errors.cpuDemand ? 'wl-cpu-err' : undefined} />
        {errors.cpuDemand && <p id="wl-cpu-err" className="mt-1 text-xs text-crit">{errors.cpuDemand}</p>}
      </div>
      <div>
        <label htmlFor="wl-priority" className="mb-1.5 block text-sm font-medium text-fg-soft">Priority</label>
        <select id="wl-priority" className={inputCls} value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value })}>
          <option>LOW</option>
          <option>MEDIUM</option>
          <option>HIGH</option>
        </select>
      </div>
      <div className="sm:pt-[26px]">
        <Button type="submit" variant="primary" disabled={busy}>
          <Plus className="h-4 w-4" aria-hidden /> {busy ? 'Submitting…' : 'Submit'}
        </Button>
      </div>
    </form>
  );
}

export default function Workloads() {
  const { role } = useAuth();
  const canWrite = hasRole(role, 'OPERATOR');
  const toast = useToast();
  const qc = useQueryClient();
  const all = useWorkloads();
  const summary = useWorkloadSummary();
  const sched = useSchedulerStatus();
  const topo = useTopology();

  const [q, setQ] = useState('');
  const [status, setStatus] = useState('ALL');
  const [priority, setPriority] = useState('ALL');
  const [rack, setRack] = useState('ALL');
  const [toRemove, setToRemove] = useState(null);
  const [removing, setRemoving] = useState(false);
  const [explain, setExplain] = useState(null);

  const rackIds = useMemo(() => (topo.data || []).flatMap((z) => z.racks.map((r) => r.id)), [topo.data]);
  const list = all.data || [];
  const visible = useMemo(
    () =>
      list
        .filter((w) => (status === 'ALL' ? w.status !== 'REMOVED' : w.status === status))
        .filter((w) => priority === 'ALL' || w.priority === priority)
        .filter((w) => rack === 'ALL' || w.rackId === rack || (rack === 'NONE' && !w.rackId))
        .filter((w) => !q.trim() || w.name.toLowerCase().includes(q.trim().toLowerCase()) || String(w.id) === q.trim()),
    [list, q, status, priority, rack],
  );
  const pending = list.filter((w) => w.status === 'PENDING');
  const stacks = useMemo(() => buildRackStacks(list.filter((w) => w.status === 'RUNNING'), rackIds), [list, rackIds]);

  const doRemove = async () => {
    setRemoving(true);
    try {
      await removeWorkload(toRemove.id);
      toast.success(`Removed ${toRemove.name}`, 'The simulator stops counting its heat.');
      qc.invalidateQueries({ queryKey: ['workloads'] });
      qc.invalidateQueries({ queryKey: ['workload-summary'] });
      setToRemove(null);
    } catch (err) {
      toast.error('Could not remove the workload', errorMessage(err));
    } finally {
      setRemoving(false);
    }
  };

  const shortage = sched.hasData && sched.data.capacityShortage;

  return (
    <div className="space-y-5">
      {shortage && (
        <div className="flex items-start gap-3 rounded-xl border border-warn/40 bg-warn/10 px-4 py-3.5" role="alert">
          <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-warn" aria-hidden />
          <div>
            <p className="font-semibold text-warn">Capacity shortage</p>
            <p className="text-sm text-fg-soft">No rack currently passes every placement filter (85% CPU cap, forecast headroom, healthy cooling), so {sched.data.pendingWorkloads} workload{sched.data.pendingWorkloads === 1 ? ' stays' : 's stay'} pending. The scheduler retries every 3 seconds.</p>
          </div>
        </div>
      )}

      {canWrite && (
        <Panel title="Submit a workload" subtitle="It is saved as PENDING and placed by the scheduler on the best available rack" sources={[]}>
          <SubmitForm />
        </Panel>
      )}

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
        <Panel
          title="Workloads"
          subtitle={all.hasData ? `${visible.length} shown of ${list.filter((w) => w.status !== 'REMOVED').length}` : undefined}
          sources={[all]}
          skeleton={<Skeleton className="h-[360px]" />}
          flush
        >
          <div className="flex flex-wrap items-center gap-3 border-b border-ink-600/70 px-5 py-3.5">
            <div className="relative min-w-[200px] flex-1">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-fg-faint" aria-hidden />
              <input aria-label="Search workloads by name or id" className={cx(inputCls, 'pl-9')} placeholder="Search name or id" value={q} onChange={(e) => setQ(e.target.value)} />
            </div>
            <Segmented label="Status" value={status} onChange={setStatus} options={[{ value: 'ALL', label: 'All' }, { value: 'RUNNING', label: 'Running' }, { value: 'PENDING', label: 'Pending' }]} />
            <select aria-label="Filter by priority" className={cx(inputCls, '!w-auto')} value={priority} onChange={(e) => setPriority(e.target.value)}>
              <option value="ALL">Any priority</option>
              <option>LOW</option>
              <option>MEDIUM</option>
              <option>HIGH</option>
            </select>
            <select aria-label="Filter by rack" className={cx(inputCls, '!w-auto')} value={rack} onChange={(e) => setRack(e.target.value)}>
              <option value="ALL">Any rack</option>
              <option value="NONE">Unplaced</option>
              {rackIds.map((r) => (
                <option key={r}>{r}</option>
              ))}
            </select>
          </div>
          {visible.length === 0 ? (
            <EmptyState icon={Boxes} title="No workloads match" body={list.length === 0 ? 'Seed the demo workloads from the Control Room, or submit one above.' : 'Try clearing a filter.'} />
          ) : (
            <div className="max-h-[560px] overflow-auto">
              <table className="w-full text-[15px]">
                <thead className="sticky top-0 bg-ink-850 text-xs uppercase tracking-wide text-fg-mute shadow-[0_1px_0_0_var(--color-ink-600)]">
                  <tr>
                    <th className="px-5 py-2.5 text-left font-semibold">Workload</th>
                    <th className="px-3 py-2.5 text-left font-semibold">Priority</th>
                    <th className="px-3 py-2.5 text-right font-semibold">CPU</th>
                    <th className="px-3 py-2.5 text-left font-semibold">Status</th>
                    <th className="px-3 py-2.5 text-left font-semibold">Rack</th>
                    <th className="px-5 py-2.5 text-right font-semibold"><span className="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-ink-600/60">
                  {visible.map((w) => (
                    <tr key={w.id} className="transition-colors hover:bg-ink-800/70">
                      <td className="px-5 py-2.5">
                        <p className="font-medium text-fg">{w.name}</p>
                        <p className="num text-xs text-fg-mute">#{w.id}</p>
                      </td>
                      <td className="px-3 py-2.5"><Tag tone={PRIORITY_TONE[w.priority]}>{w.priority}</Tag></td>
                      <td className="num px-3 py-2.5 text-right text-fg-soft">{w.cpuDemand}</td>
                      <td className="px-3 py-2.5"><Tag tone={STATUS_TONE[w.status]}>{w.status}</Tag></td>
                      <td className="px-3 py-2.5 font-mono">{w.rackId ? <Link to={`/racks/${w.rackId}`} className="text-fg hover:text-cyan">{w.rackId}</Link> : <span className="text-fg-faint">—</span>}</td>
                      <td className="px-5 py-2.5">
                        <div className="flex justify-end gap-1">
                          <button type="button" onClick={() => setExplain(w)} className="rounded-md p-1.5 text-fg-mute transition hover:bg-ink-700 hover:text-cyan" aria-label={`Why is ${w.name} placed where it is?`} title="Why this rack?">
                            <HelpCircle className="h-4 w-4" />
                          </button>
                          {canWrite && (
                            <button type="button" onClick={() => setToRemove(w)} className="rounded-md p-1.5 text-fg-mute transition hover:bg-crit/10 hover:text-crit" aria-label={`Remove ${w.name}`} title="Remove">
                              <Trash2 className="h-4 w-4" />
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>

        <div className="space-y-5">
          <Panel title="Pending queue" subtitle="Waiting for a rack that passes every filter" sources={[all]} skeleton={<Skeleton className="h-24" />}>
            {pending.length === 0 ? (
              <EmptyState title="Nothing is waiting" body="Every submitted workload has been placed." />
            ) : (
              <ul className="divide-y divide-ink-600/70">
                {pending.map((w) => (
                  <li key={w.id} className="flex items-center justify-between py-2.5">
                    <div>
                      <p className="font-medium text-fg">{w.name}</p>
                      <p className="num text-xs text-fg-mute">#{w.id} · {w.cpuDemand} CPU units</p>
                    </div>
                    <Tag tone={PRIORITY_TONE[w.priority]}>{w.priority}</Tag>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
          <Panel title="CPU by rack" subtitle={summary.hasData ? `${summary.data.totalCpuUnits} units across ${summary.data.runningCount} running workloads` : undefined} sources={[all, summary]} skeleton={<Skeleton className="h-[300px]" />}>
            <CpuByRackChart data={stacks} height={300} />
          </Panel>
        </div>
      </div>

      <ConfirmDialog
        open={!!toRemove}
        title="Remove this workload?"
        confirmLabel="Remove workload"
        busy={removing}
        onConfirm={doRemove}
        onCancel={() => setToRemove(null)}
        body={
          <>
            <p><span className="font-semibold text-fg">{toRemove?.name}</span> ({toRemove?.cpuDemand} CPU units{toRemove?.rackId ? `, on ${toRemove.rackId}` : ''}) will be marked REMOVED and its heat disappears from the rack.</p>
          </>
        }
      />
      <PlacementExplainer workloadId={explain?.id} workloadName={explain?.name} onClose={() => setExplain(null)} />
    </div>
  );
}
