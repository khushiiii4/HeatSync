package com.thermogrid.scheduler.engine;

import com.thermogrid.scheduler.config.Tuning;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Cooldown rules of plan section 6D, evaluated at a fixed instant. Built once per cycle from the migration table
 * (APPLIED moves and recent FAILED attempts, so cooldowns survive a restart) and the alert events seen recently.
 *
 * <ul>
 * <li>per workload: 120 s after any applied move;</li>
 * <li>per source rack: 20 s after migrating out of it (10 s if the rack is CRITICAL);</li>
 * <li>destination quarantine: 60 s after a rack was a source, or raised an alert;</li>
 * <li>failure backoff: a workload whose move just failed is left alone for a few seconds (no retry storm).</li>
 * </ul>
 */
public class CooldownTracker {

    /** An applied move: which workload, out of which rack, when. */
    public record Move(long workloadId, String fromRackId, Instant at) {
    }

    private final Instant now;
    private final Tuning t;
    private final Map<Long, Instant> lastMoveOfWorkload = new HashMap<>();
    private final Map<String, Instant> lastMoveOutOfRack = new HashMap<>();
    private final Map<Long, Instant> lastFailureOfWorkload = new HashMap<>();
    private final Map<String, Instant> lastAlertOfRack = new HashMap<>();

    public CooldownTracker(Instant now, Tuning tuning, Collection<Move> appliedMoves,
                           Map<Long, Instant> failedAttempts, Map<String, Instant> alertRacks) {
        this.now = now;
        this.t = tuning;
        for (Move m : appliedMoves) {
            lastMoveOfWorkload.merge(m.workloadId(), m.at(), CooldownTracker::later);
            if (m.fromRackId() != null) {
                lastMoveOutOfRack.merge(m.fromRackId(), m.at(), CooldownTracker::later);
            }
        }
        lastFailureOfWorkload.putAll(failedAttempts);
        lastAlertOfRack.putAll(alertRacks);
    }

    public static CooldownTracker empty(Instant now, Tuning tuning) {
        return new CooldownTracker(now, tuning, List.of(), Map.of(), Map.of());
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private boolean within(Instant at, long seconds) {
        return at != null && Duration.between(at, now).compareTo(Duration.ofSeconds(seconds)) < 0;
    }

    public boolean workloadCoolingDown(long workloadId) {
        return within(lastMoveOfWorkload.get(workloadId), t.workloadCooldownSec());
    }

    public boolean failedRecently(long workloadId) {
        return within(lastFailureOfWorkload.get(workloadId), t.failureBackoffSec());
    }

    public boolean sourceCoolingDown(String rackId, boolean critical) {
        return within(lastMoveOutOfRack.get(rackId),
                critical ? t.sourceCooldownCriticalSec() : t.sourceCooldownSec());
    }

    /** A rack that was a source or raised an alert in the last 60 s is not a destination. */
    public boolean quarantined(String rackId) {
        return within(lastMoveOutOfRack.get(rackId), t.quarantineSec())
                || within(lastAlertOfRack.get(rackId), t.quarantineSec());
    }

    /** Racks currently inside their source cooldown (reported by the status endpoint), sorted. */
    public Set<String> cooldownRacks() {
        Set<String> out = new TreeSet<>();
        for (String r : new HashSet<>(lastMoveOutOfRack.keySet())) {
            if (sourceCoolingDown(r, false)) {
                out.add(r);
            }
        }
        return out;
    }
}
