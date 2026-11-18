import { useEffect, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { serverDates, failedService } from './client';
import { acquireSource, releaseSource, reportSource } from '../state/sourceHealth';

/**
 * One polled data source = one TanStack Query with its own refetchInterval (plan section 9.5).
 *
 * Behaviour that matters for the resilience demo:
 *  - the last successful data is kept when a refresh fails (TanStack keeps `data` on error);
 *  - `stale` is true when we hold data but the latest refresh failed;
 *  - `lastServerTime` is the server's timestamp for that data: taken from the payload (`stamp`) or, failing that,
 *    from the response Date header. It is never the browser clock;
 *  - `frozen` is true when the request succeeds but the data's own server timestamp lags the server's clock by more
 *    than `freezeMs` (for example the simulator stopped while telemetry is up);
 *  - polling pauses when the tab is hidden (refetchIntervalInBackground is false).
 */
export function usePolled({ key, fn, interval, service, stamp, freezeMs, enabled = true, path }) {
  const q = useQuery({
    queryKey: key,
    queryFn: fn,
    refetchInterval: interval,
    refetchIntervalInBackground: false,
    retry: 0,
    staleTime: interval ? Math.max(500, interval / 2) : Infinity,
    enabled,
  });

  // Remember the server time of the last good payload; a failed refresh must not overwrite it.
  const lastGood = useRef(null);
  if (q.data !== undefined && q.dataUpdatedAt) {
    const fromPayload = stamp ? stamp(q.data) : null;
    lastGood.current = fromPayload || serverDates.get(path) || lastGood.current;
  }

  const hasData = q.data !== undefined;
  const stale = q.isError && hasData;
  const down = q.isError && !hasData;
  const serverNow = path ? serverDates.get(path) : null;
  const frozen =
    !q.isError && hasData && freezeMs && lastGood.current && serverNow
      ? Date.parse(serverNow) - Date.parse(lastGood.current) > freezeMs
      : false;

  const id = key.join(':');
  const failing = q.isError;
  useEffect(() => {
    reportSource(id, { failing, service: service || failedService(q.error) || null });
  }, [id, failing, service, q.error]);
  useEffect(() => {
    acquireSource(id);
    return () => releaseSource(id);
  }, [id]);

  return {
    key: id,
    service,
    data: q.data,
    isLoading: q.isLoading,
    isFetching: q.isFetching,
    isError: q.isError,
    error: q.error,
    hasData,
    stale,
    down,
    frozen,
    lastServerTime: lastGood.current,
    refetch: q.refetch,
  };
}

/** Combine several sources into one status for a panel that draws from more than one. */
export function combineSources(sources) {
  const failing = sources.filter((s) => s.isError);
  return {
    sources,
    loading: sources.some((s) => s.isLoading && !s.hasData),
    anyStale: sources.some((s) => s.stale || s.frozen),
    anyDown: sources.some((s) => s.down),
    allDown: sources.length > 0 && sources.every((s) => s.down),
    failing,
    lastServerTime: sources
      .map((s) => s.lastServerTime)
      .filter(Boolean)
      .sort()
      .pop() || null,
  };
}
