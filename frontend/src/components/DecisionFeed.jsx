import { useMemo, useState } from 'react';
import { AlertTriangle, ArrowRightLeft, CheckCircle2, Fan, HelpCircle, ScrollText } from 'lucide-react';
import { useCoolingActions, useMigrations, useWorkloads } from '../api/sources';
import { mergeFeeds } from '../lib/mergeFeeds';
import { fmtClock } from '../lib/format';
import { Panel } from './Panel';
import { EmptyState, Segmented, Skeleton, Tag, cx } from './ui';

const KIND = {
  migration: { icon: ArrowRightLeft, tone: 'text-cyan', ring: 'bg-cyan/10 border-cyan/30' },
  cooling: { icon: Fan, tone: 'text-sky', ring: 'bg-azure/10 border-azure/30' },
  alert: { icon: AlertTriangle, tone: 'text-orange', ring: 'bg-orange/10 border-orange/30' },
};

const OUTCOME_TONE = { APPLIED: 'ok', FAILED: 'crit', PLANNED: 'neutral', SKIPPED: 'neutral', AUTO: 'neutral', MANUAL: 'warn', FAILSAFE: 'crit', ACTIVE: 'orange', CLEARED: 'ok' };

export function FeedItem({ item, onExplain }) {
  const k = KIND[item.kind];
  const Icon = item.severity === 'ok' ? CheckCircle2 : k.icon;
  return (
    <li className="flex gap-3 py-3">
      <div className={cx('mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border', k.ring)}>
        <Icon className={cx('h-4 w-4', item.severity === 'ok' ? 'text-ok' : item.severity === 'crit' ? 'text-crit' : k.tone)} aria-hidden />
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-start justify-between gap-3">
          <p className="text-[15px] font-medium leading-snug text-fg">{item.title}</p>
          <time className="num shrink-0 pt-0.5 text-xs text-fg-mute" dateTime={item.at}>
            {fmtClock(item.at)}
          </time>
        </div>
        {item.reason && <p className="mt-0.5 text-sm leading-snug text-fg-soft">{item.reason}</p>}
        <div className="mt-1.5 flex flex-wrap items-center gap-2">
          <Tag tone={OUTCOME_TONE[item.outcome] || 'neutral'}>{item.outcome}</Tag>
          {item.detail && <span className="text-xs text-crit">{item.detail}</span>}
          {item.kind === 'migration' && onExplain && (
            <button type="button" onClick={() => onExplain(item.workloadId)} className="inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs font-medium text-fg-mute transition hover:bg-ink-700 hover:text-cyan">
              <HelpCircle className="h-3.5 w-3.5" aria-hidden /> Why this rack?
            </button>
          )}
        </div>
      </div>
    </li>
  );
}

/** Migrations and cooling actions merged by time (plan 9.4). Small controller trims are hidden by default. */
export function DecisionFeed({ onExplain, className = '' }) {
  const migrations = useMigrations(20);
  const actions = useCoolingActions(20);
  const running = useWorkloads({ status: 'RUNNING' });
  const [filter, setFilter] = useState('ALL');
  const [showMinor, setShowMinor] = useState(false);

  const { items, hiddenCooling } = useMemo(() => {
    const merged = mergeFeeds({
      migrations: filter === 'COOLING' ? [] : migrations.data || [],
      actions: filter === 'MIGRATIONS' ? [] : actions.data || [],
      workloads: running.data || [],
      minCoolingStep: showMinor ? 0 : 5,
      limit: 30,
    });
    return merged;
  }, [migrations.data, actions.data, running.data, filter, showMinor]);

  return (
    <Panel
      title="Decision feed"
      subtitle="What the system did and why, newest first"
      sources={[migrations, actions]}
      className={className}
      skeleton={
        <div className="space-y-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-14" />
          ))}
        </div>
      }
      actions={<Segmented label="Feed filter" value={filter} onChange={setFilter} options={[{ value: 'ALL', label: 'All' }, { value: 'MIGRATIONS', label: 'Moves' }, { value: 'COOLING', label: 'Cooling' }]} />}
    >
      {items.length === 0 ? (
        <EmptyState icon={ScrollText} title="No decisions yet" body="Migrations and cooling changes will appear here with the reason for each." />
      ) : (
        <ul className="max-h-[520px] divide-y divide-ink-600/70 overflow-y-auto pr-1">
          {items.map((item) => (
            <FeedItem key={item.id} item={item} onExplain={onExplain} />
          ))}
        </ul>
      )}
      {filter !== 'MIGRATIONS' && (hiddenCooling > 0 || showMinor) && (
        <button type="button" onClick={() => setShowMinor((v) => !v)} className="mt-3 w-full rounded-lg border border-dashed border-ink-500 py-2 text-xs font-medium text-fg-mute transition hover:border-cyan/40 hover:text-cyan">
          {showMinor ? 'Hide small cooling adjustments' : `Show ${hiddenCooling} small cooling adjustment${hiddenCooling === 1 ? '' : 's'} (under 5 points)`}
        </button>
      )}
    </Panel>
  );
}
