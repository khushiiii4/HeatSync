package com.thermogrid.scheduler.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plain value types of the scheduling domain. Nothing here depends on Spring, Feign or the database. */
public final class Model {

    private Model() {
    }

    public enum Priority {
        LOW, MEDIUM, HIGH;

        public static Priority parse(String s) {
            try {
                return valueOf(s);
            } catch (RuntimeException e) {
                return MEDIUM;
            }
        }
    }

    public enum RiskLevel {
        NORMAL, WATCH, WARNING, CRITICAL;

        public static RiskLevel parse(String s) {
            try {
                return valueOf(s);
            } catch (RuntimeException e) {
                return NORMAL;
            }
        }

        public boolean atLeastWarning() {
            return this == WARNING || this == CRITICAL;
        }
    }

    /** NORMAL: telemetry available. HOLD: telemetry unreachable, CPU-only placement and no thermal migration. */
    public enum Mode {
        NORMAL, HOLD
    }

    /** Values of migration.reason (plan section 7). Same names as the contract's workload.assigned reasons. */
    public enum Reason {
        INITIAL_PLACEMENT, THERMAL_WARNING, THERMAL_CRITICAL, COOLING_FAILURE, REBALANCE
    }

    /**
     * One rack as the scheduler sees it. {@code forecast60C} is null when telemetry has no value for the rack (and in
     * HOLD mode); {@code sensorOk} is false for a STALE or unknown sensor.
     */
    public record Rack(String id, String zoneId, int capacity, boolean active, Double forecast60C, Double tempC,
                       RiskLevel risk, boolean sensorOk) {
    }

    public record Job(long id, String name, int cpu, Priority priority, String rackId, int version) {
    }

    /** A fresh, immutable picture of the cluster for one cycle. */
    public static final class ClusterView {
        private final Instant at;
        private final Mode mode;
        private final List<Rack> racks;
        private final Set<String> faultZones;
        private final List<Job> running;
        private final List<Job> pending;
        private final Map<String, Integer> used = new HashMap<>();
        private final Map<String, Integer> zoneUsed = new HashMap<>();
        private final Map<String, Integer> zoneCapacity = new HashMap<>();

        public ClusterView(Instant at, Mode mode, List<Rack> racks, Set<String> faultZones, List<Job> running,
                           List<Job> pending) {
            this.at = at;
            this.mode = mode;
            this.racks = List.copyOf(racks);
            this.faultZones = Set.copyOf(faultZones);
            this.running = List.copyOf(running);
            this.pending = List.copyOf(pending);
            Map<String, String> zoneOf = new HashMap<>();
            for (Rack r : this.racks) {
                zoneOf.put(r.id(), r.zoneId());
                zoneCapacity.merge(r.zoneId(), r.capacity(), Integer::sum);
            }
            for (Job j : this.running) {
                used.merge(j.rackId(), j.cpu(), Integer::sum);
                String z = zoneOf.get(j.rackId());
                if (z != null) {
                    zoneUsed.merge(z, j.cpu(), Integer::sum);
                }
            }
        }

        public Instant at() { return at; }
        public Mode mode() { return mode; }
        public List<Rack> racks() { return racks; }
        public Set<String> faultZones() { return faultZones; }
        public List<Job> running() { return running; }
        public List<Job> pending() { return pending; }

        public int cpuUsed(String rackId) {
            return used.getOrDefault(rackId, 0);
        }

        /** Fraction (0 to 1) of the zone's capacity in use, before any reservation. */
        public double zoneUtilization(String zoneId) {
            int cap = zoneCapacity.getOrDefault(zoneId, 0);
            return cap == 0 ? 0.0 : (double) zoneUsed.getOrDefault(zoneId, 0) / cap;
        }

        public List<Job> jobsOn(String rackId) {
            return running.stream().filter(j -> rackId.equals(j.rackId())).toList();
        }
    }
}
