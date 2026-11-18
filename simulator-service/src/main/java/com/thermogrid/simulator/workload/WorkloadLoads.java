package com.thermogrid.simulator.workload;

import com.thermogrid.simulator.sim.LoadSource;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The simulator's view of which workload heats which rack. Heat sources are only ever changed by workload events and
 * by the periodic full resync; nothing else writes here.
 *
 * <p>Rules: an assigned event older than or equal to the stored version of the same workload is ignored (duplicates and
 * out-of-order delivery); a removed workload is remembered for a while so a late assigned event cannot resurrect it;
 * {@link #rackLoads()} is null until the first successful bootstrap, so the engine keeps its static load until then.
 */
public class WorkloadLoads implements LoadSource {

    private static final Logger log = LoggerFactory.getLogger(WorkloadLoads.class);

    private record Entry(String rackId, int cpu, int version, Instant touchedAt) {
    }

    private final Clock clock;
    private final long tombstoneTtlMs;
    private final Map<Long, Entry> entries = new HashMap<>();
    private final Map<Long, Instant> tombstones = new HashMap<>();
    private boolean loaded;
    private volatile Map<String, Double> cached;   // immutable per-rack totals, null until loaded

    public WorkloadLoads(Clock clock, long tombstoneTtlMs) {
        this.clock = clock;
        this.tombstoneTtlMs = tombstoneTtlMs;
    }

    @Override
    public Map<String, Double> rackLoads() {
        return cached;
    }

    public synchronized boolean isLoaded() {
        return loaded;
    }

    public synchronized int workloadCount() {
        return entries.size();
    }

    /** @return true if the event changed the state */
    public synchronized boolean applyAssigned(long workloadId, String toRackId, int cpuDemand, int version) {
        if (toRackId == null || toRackId.isBlank() || cpuDemand < 0) {
            log.warn("Ignoring workload.assigned {} with rack {} and cpu {}", workloadId, toRackId, cpuDemand);
            return false;
        }
        if (tombstones.containsKey(workloadId)) {
            return false;
        }
        Entry old = entries.get(workloadId);
        if (old != null && version <= old.version()) {
            return false;
        }
        entries.put(workloadId, new Entry(toRackId, cpuDemand, version, clock.instant()));
        rebuild();
        return true;
    }

    /** @return true if the workload was a known heat source */
    public synchronized boolean applyRemoved(long workloadId) {
        tombstones.put(workloadId, clock.instant());
        boolean known = entries.remove(workloadId) != null;
        rebuild();
        return known;
    }

    /**
     * Replaces the state with the authoritative list of RUNNING workloads. Events that were applied after the fetch
     * started (newer version, or a workload the list could not know about) are kept. Marks the data as loaded.
     */
    public synchronized void reconcile(Collection<WorkloadView> running, Instant fetchStartedAt) {
        Instant now = clock.instant();
        for (Iterator<Instant> it = tombstones.values().iterator(); it.hasNext();) {
            if (it.next().plusMillis(tombstoneTtlMs).isBefore(now)) {
                it.remove();
            }
        }
        Map<Long, Entry> next = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        for (WorkloadView w : running) {
            if (w.rackId() == null || w.rackId().isBlank() || tombstonedAfter(w.id(), fetchStartedAt)) {
                continue;
            }
            seen.add(w.id());
            Entry local = entries.get(w.id());
            if (local != null && local.version() > w.version()) {
                next.put(w.id(), local);
            } else {
                next.put(w.id(), new Entry(w.rackId(), w.cpuDemand(), w.version(),
                        local != null && local.version() == w.version() ? local.touchedAt() : now));
            }
        }
        for (var e : entries.entrySet()) {
            if (!seen.contains(e.getKey()) && e.getValue().touchedAt().isAfter(fetchStartedAt)
                    && !tombstones.containsKey(e.getKey())) {
                next.put(e.getKey(), e.getValue());
            }
        }
        int before = entries.size();
        entries.clear();
        entries.putAll(next);
        boolean first = !loaded;
        loaded = true;
        rebuild();
        if (first || before != entries.size()) {
            log.info("Workloads {}: {} running ({} before)", first ? "bootstrapped" : "reconciled", entries.size(),
                    before);
        }
    }

    /** A removal recorded after the fetch began wins over the (older) list; older tombstones were pruned or are stale. */
    private boolean tombstonedAfter(long id, Instant fetchStartedAt) {
        Instant t = tombstones.get(id);
        return t != null && t.isAfter(fetchStartedAt);
    }

    private void rebuild() {
        Map<String, Double> totals = new HashMap<>();
        for (Entry e : entries.values()) {
            totals.merge(e.rackId(), (double) e.cpu(), Double::sum);
        }
        // Partial data must never drive the physics: totals are published only once the bootstrap has loaded.
        cached = loaded ? Map.copyOf(totals) : null;
    }
}
