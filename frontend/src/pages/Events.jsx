import { useMemo, useState } from 'react';
import { History } from 'lucide-react';
import { useAlerts, useCoolingActions, useMigrations, useWorkloads } from '../api/sources';
import { mergeFeeds } from '../lib/mergeFeeds';
import { FeedItem } from '../components/DecisionFeed';
import { Panel } from '../components/Panel';
import { EmptyState, Segmented, Skeleton } from '../components/ui';

/** Alerts, migrations and cooling actions merged into one timeline (plan 9.1, optional page, implemented). */
export default function Events() {
  const alerts = useAlerts('ALL', 200);
  const migrations = useMigrations(100);
  const actions = useCoolingActions(200);
  const running = useWorkloads({ status: 'RUNNING' });
  const [kind, setKind] = useState('ALL');
  const [minor, setMinor] = useState(false);

  const { items, hiddenCooling } = useMemo(
    () =>
      mergeFeeds({
        alerts: kind === 'ALL' || kind === 'ALERTS' ? alerts.data || [] : [],
        migrations: kind === 'ALL' || kind === 'MOVES' ? migrations.data || [] : [],
        actions: kind === 'ALL' || kind === 'COOLING' ? actions.data || [] : [],
        workloads: running.data || [],
        minCoolingStep: minor ? 0 : 5,
        limit: 200,
      }),
    [alerts.data, migrations.data, actions.data, running.data, kind, minor],
  );

  return (
    <Panel
      title="Event history"
      subtitle="Alerts from telemetry, moves from the scheduler and output changes from the cooling controller"
      sources={[alerts, migrations, actions]}
      skeleton={<Skeleton className="h-[480px]" />}
      actions={
        <>
          <Segmented label="Event kind" value={kind} onChange={setKind} options={[{ value: 'ALL', label: 'All' }, { value: 'ALERTS', label: 'Alerts' }, { value: 'MOVES', label: 'Moves' }, { value: 'COOLING', label: 'Cooling' }]} />
          <Segmented label="Cooling detail" value={minor ? 'all' : 'key'} onChange={(v) => setMinor(v === 'all')} options={[{ value: 'key', label: 'Key changes' }, { value: 'all', label: 'Every step' }]} />
        </>
      }
    >
      {items.length === 0 ? (
        <EmptyState icon={History} title="No events yet" body="Inject a failure from the Control Room to see the system respond." />
      ) : (
        <>
          <ul className="max-h-[720px] divide-y divide-ink-600/70 overflow-y-auto pr-2">
            {items.map((item) => (
              <FeedItem key={item.id} item={item} />
            ))}
          </ul>
          {!minor && hiddenCooling > 0 && kind !== 'MOVES' && kind !== 'ALERTS' && <p className="mt-3 text-center text-xs text-fg-mute">{hiddenCooling} small cooling adjustments (under 5 points) are hidden. Choose “Every step” to see them.</p>}
        </>
      )}
    </Panel>
  );
}
