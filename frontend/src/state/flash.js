import { useSyncExternalStore } from 'react';

// Racks involved in a new migration flash for two seconds (plan 9.5). A tiny store so the watcher (mounted once in
// the shell) can drive the heat map wherever it is on screen.
let flashing = new Set();
const listeners = new Set();
const emit = () => listeners.forEach((l) => l());

export function flashRacks(ids, ms = 2000) {
  const add = ids.filter(Boolean);
  if (!add.length) return;
  flashing = new Set([...flashing, ...add]);
  emit();
  setTimeout(() => {
    const next = new Set(flashing);
    add.forEach((id) => next.delete(id));
    flashing = next;
    emit();
  }, ms);
}

const subscribe = (l) => {
  listeners.add(l);
  return () => listeners.delete(l);
};
export const useFlashing = () =>
  useSyncExternalStore(
    subscribe,
    () => flashing,
    () => flashing,
  );
