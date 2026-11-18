import { useMemo } from 'react';
import { Trophy } from 'lucide-react';
import { useDecisions } from '../api/sources';
import { errorMessage } from '../api/client';
import { fmtDateTime } from '../lib/format';
import { Modal, Skeleton, Tag, cx } from './ui';

/** Answers "why was this rack chosen?" from the scheduler's recorded decisions (fetched when opened, plan 9.4). */
export function PlacementExplainer({ workloadId, workloadName, onClose }) {
  const open = workloadId != null;
  const q = useDecisions(open);
  const decisions = useMemo(() => (q.data || []).filter((d) => d.workloadId === workloadId), [q.data, workloadId]);

  return (
    <Modal open={open} onClose={onClose} title={`Why this rack? ${workloadName ? `· ${workloadName}` : `· workload #${workloadId}`}`} width="max-w-2xl">
      {q.isLoading ? (
        <div className="space-y-3">
          <Skeleton className="h-24" />
          <Skeleton className="h-24" />
        </div>
      ) : q.isError ? (
        <p className="text-sm text-warn" role="alert">
          {errorMessage(q.error)}. Placement explanations come from the scheduler.
        </p>
      ) : decisions.length === 0 ? (
        <p className="text-[15px] leading-relaxed text-fg-soft">
          The scheduler has no recorded placement decision for this workload. Workloads created by the demo seed are assigned directly to racks, and
          migrations are explained by their reason in the decision feed; only placements the scheduler made itself are scored here.
        </p>
      ) : (
        <ul className="space-y-5">
          {decisions.map((d) => (
            <li key={d.id}>
              <div className="mb-2.5 flex flex-wrap items-center gap-2">
                <Tag tone="cyan">Chose {d.chosenRackId}</Tag>
                <Tag tone={d.mode === 'HOLD' ? 'orange' : 'neutral'}>{d.mode} mode</Tag>
                <span className="num text-xs text-fg-mute">{fmtDateTime(d.createdAt)}</span>
              </div>
              <div className="overflow-hidden rounded-xl border border-ink-600">
                <table className="w-full text-sm">
                  <caption className="sr-only">Top candidate racks, lowest score wins</caption>
                  <thead className="bg-ink-800 text-xs uppercase tracking-wide text-fg-mute">
                    <tr>
                      <th className="px-3 py-2 text-left font-semibold">Rack</th>
                      <th className="px-3 py-2 text-right font-semibold">Score</th>
                      <th className="px-3 py-2 text-right font-semibold">Temp after</th>
                      <th className="px-3 py-2 text-right font-semibold">Utilization after</th>
                    </tr>
                  </thead>
                  <tbody className="num divide-y divide-ink-600/70">
                    {d.candidates.map((c) => (
                      <tr key={c.rackId} className={cx(c.rackId === d.chosenRackId && 'bg-cyan/5')}>
                        <td className="px-3 py-2 font-mono font-semibold text-fg">
                          <span className="inline-flex items-center gap-2">
                            {c.rackId === d.chosenRackId && <Trophy className="h-3.5 w-3.5 text-cyan" aria-label="chosen" />}
                            {c.rackId}
                          </span>
                        </td>
                        <td className="px-3 py-2 text-right text-fg">{Number(c.score).toFixed(3)}</td>
                        <td className="px-3 py-2 text-right text-fg-soft">{c.tAfterC != null ? `${Number(c.tAfterC).toFixed(1)} °C` : '—'}</td>
                        <td className="px-3 py-2 text-right text-fg-soft">{c.utilAfter != null ? `${Math.round(c.utilAfter * 100)}%` : '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="mt-2 text-xs leading-relaxed text-fg-mute">
                Score = 0.6 × temperature after placement + 0.3 × utilization after + 0.1 × zone utilization; the lowest score wins and near-ties go to the rack with less free capacity (best fit).
              </p>
            </li>
          ))}
        </ul>
      )}
    </Modal>
  );
}
