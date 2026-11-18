package com.thermogrid.scheduler.service;

import com.thermogrid.scheduler.model.Model.Mode;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory facts about the scheduler that the status endpoint reports: mode, autopilot flag, last cycle, pending count,
 * capacity shortage and racks in cooldown. Also remembers which racks recently raised a WARNING or CRITICAL alert
 * (destination quarantine, plan 6D). All of it is rebuilt within one cycle after a restart.
 */
@Component
public class SchedulerState {

    private volatile Mode mode = Mode.NORMAL;
    private volatile boolean autopilot = true;
    private volatile Instant lastCycleAt;
    private volatile int pendingCount;
    private volatile boolean capacityShortage;
    private volatile Set<String> cooldownRacks = Set.of();
    private final Map<String, Instant> alertRacks = new ConcurrentHashMap<>();

    public Mode mode() { return mode; }
    public void mode(Mode m) { this.mode = m; }

    public boolean autopilot() { return autopilot; }
    public void autopilot(boolean on) { this.autopilot = on; }

    public Instant lastCycleAt() { return lastCycleAt; }
    public void lastCycleAt(Instant t) { this.lastCycleAt = t; }

    public int pendingCount() { return pendingCount; }
    public boolean capacityShortage() { return capacityShortage; }

    public void pending(int count, boolean shortage) {
        this.pendingCount = count;
        this.capacityShortage = shortage;
    }

    public Set<String> cooldownRacks() { return cooldownRacks; }

    public void cooldownRacks(Set<String> racks) {
        this.cooldownRacks = Collections.unmodifiableSet(new TreeSet<>(racks));
    }

    /** A WARNING or CRITICAL alert was raised on the rack at this time. */
    public void alertRaised(String rackId, Instant at) {
        alertRacks.merge(rackId, at, (a, b) -> a.isAfter(b) ? a : b);
    }

    /** Forgets all alert-rack marks (used by tests; a restart does the same). */
    public void clearAlerts() {
        alertRacks.clear();
    }

    public Map<String, Instant> alertRacks() {
        return Map.copyOf(alertRacks);
    }
}
