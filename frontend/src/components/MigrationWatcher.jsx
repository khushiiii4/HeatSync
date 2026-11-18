import { useEffect, useRef } from 'react';
import { useMigrations, useWorkloads } from '../api/sources';
import { flashRacks } from '../state/flash';
import { migrationItem, indexWorkloads } from '../lib/mergeFeeds';
import { useToast } from './Toast';

/** Announces new scheduler decisions: a toast with the reason, and a two second flash on both racks (plan 9.5). */
export function MigrationWatcher() {
  const toast = useToast();
  const migrations = useMigrations(20);
  const running = useWorkloads({ status: 'RUNNING' });
  const seen = useRef(null);

  useEffect(() => {
    if (!migrations.hasData) return;
    const ids = migrations.data.map((m) => m.id);
    if (seen.current === null) {
      seen.current = new Set(ids); // the first load is history, not news
      return;
    }
    const byId = indexWorkloads(running.data || []);
    const fresh = migrations.data.filter((m) => !seen.current.has(m.id)).reverse();
    fresh.forEach((m) => {
      seen.current.add(m.id);
      const item = migrationItem(m, byId);
      flashRacks(item.racks);
      if (m.status === 'FAILED') toast.error(item.title, m.failureReason || 'The migration failed and will be retried.');
      else toast.info(item.title, item.reason, 6000);
    });
  }, [migrations.hasData, migrations.data, running.data, toast]);

  return null;
}
