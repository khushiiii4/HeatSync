import { useSyncExternalStore } from 'react';

// A tiny store that records, per polled data source, whether it is currently failing and which service it belongs
// to. The service health strip combines this with the gateway's registry view so it can show "degraded" (the
// registry says UP but its data source is failing) as well as healthy, unavailable and stale.
// Several components can poll the same source, so entries are reference counted.
let sources = {};
const refs = {};
const listeners = new Set();
const emit = () => listeners.forEach((l) => l());

export function acquireSource(id) {
  refs[id] = (refs[id] || 0) + 1;
}

export function releaseSource(id) {
  refs[id] = (refs[id] || 1) - 1;
  if (refs[id] <= 0) {
    delete refs[id];
    if (id in sources) {
      const { [id]: _gone, ...rest } = sources;
      sources = rest;
      emit();
    }
  }
}

export function reportSource(id, info) {
  const prev = sources[id];
  if (prev && prev.failing === info.failing && prev.service === info.service) return;
  sources = { ...sources, [id]: info };
  emit();
}

const subscribe = (l) => {
  listeners.add(l);
  return () => listeners.delete(l);
};

export const useSourceHealth = () =>
  useSyncExternalStore(
    subscribe,
    () => sources,
    () => sources,
  );

export const _resetSourcesForTests = () => {
  sources = {};
  Object.keys(refs).forEach((k) => delete refs[k]);
};
