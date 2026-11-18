package com.thermogrid.scheduler.engine;

import com.thermogrid.scheduler.config.Tuning;
import com.thermogrid.scheduler.engine.PlacementEngine.Placement;
import com.thermogrid.scheduler.model.Model.ClusterView;
import com.thermogrid.scheduler.model.Model.Job;
import com.thermogrid.scheduler.model.Model.Mode;
import com.thermogrid.scheduler.model.Model.Priority;
import com.thermogrid.scheduler.model.Model.Rack;
import com.thermogrid.scheduler.model.Model.Reason;
import com.thermogrid.scheduler.model.Model.RiskLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Thermal migration planning (plan section 6D). Pure: given a fresh {@link ClusterView} and a {@link CooldownTracker}
 * it returns the moves to apply, nothing more.
 *
 * <pre>
 * shed = max(10, (forecast60(r) - 32.0) / 0.08) CPU units (CRITICAL: reference 30.0)
 * jobs = workloads on r not moved in the last 120 s, LOW, MEDIUM, HIGH, then CPU descending
 * each job gets the best destination that is not r, not a source or quarantined rack, and has capacity left after
 * the units already reserved in this cycle
 * at most 2 moves per cycle (4 if a rack is CRITICAL or a failed zone is being drained)
 * </pre>
 * A failed cooling zone is drained completely (every active rack in it is a source, no shed limit, reason
 * COOLING_FAILURE). In HOLD mode nothing is planned.
 */
public class MigrationPlanner {

    /** One planned move with the placement that explains the destination. */
    public record PlannedMove(Job job, String fromRackId, String toRackId, Reason reason, Placement placement) {
    }

    private final Tuning t;
    private final PlacementEngine engine;

    public MigrationPlanner(Tuning tuning, PlacementEngine engine) {
        this.t = tuning;
        this.engine = engine;
    }

    /** LOW before MEDIUM before HIGH, larger CPU first within a priority, then id. */
    static final Comparator<Job> MOVE_ORDER = Comparator.comparing((Job j) -> j.priority())
            .thenComparing(Comparator.comparingInt(Job::cpu).reversed())
            .thenComparingLong(Job::id);

    /** Jobs that may move now (not in their 120 s cooldown, not just failed), LOW first, larger CPU first. */
    private static List<Job> movable(List<Job> jobs, CooldownTracker cd) {
        return jobs.stream().filter(j -> !cd.workloadCoolingDown(j.id()) && !cd.failedRecently(j.id()))
                .sorted(MOVE_ORDER).toList();
    }

    /** CPU units to shed from a rack (plan 6D). */
    public double shedAmount(Double forecast60C, boolean critical) {
        double ref = critical ? t.shedRefCriticalC() : t.shedRefC();
        double f = forecast60C == null ? ref : forecast60C;
        return Math.max(t.minShedCpu(), (f - ref) / t.heatPerCpu());
    }

    public List<PlannedMove> plan(ClusterView view, CooldownTracker cd) {
        if (view.mode() == Mode.HOLD) {
            return List.of();
        }
        record Source(Rack rack, Reason reason, boolean critical, boolean draining) {
        }
        List<Source> sources = new ArrayList<>();
        for (Rack r : view.racks()) {
            if (!r.active()) {
                continue;
            }
            if (view.faultZones().contains(r.zoneId())) {
                sources.add(new Source(r, Reason.COOLING_FAILURE, false, true));
            } else if (r.sensorOk() && r.risk() == RiskLevel.CRITICAL) {
                sources.add(new Source(r, Reason.THERMAL_CRITICAL, true, false));
            } else if (r.sensorOk() && r.risk() == RiskLevel.WARNING) {
                sources.add(new Source(r, Reason.THERMAL_WARNING, false, false));
            }
        }
        if (sources.isEmpty()) {
            return List.of();
        }
        // CRITICAL first, then draining zones, then WARNING racks by forecast (hottest first).
        sources.sort(Comparator.comparingInt((Source s) -> s.critical() ? 0 : s.draining() ? 1 : 2)
                .thenComparing((Source s) -> -(s.rack().forecast60C() == null ? 0.0 : s.rack().forecast60C()))
                .thenComparing(s -> s.rack().id()));
        boolean urgent = sources.stream().anyMatch(s -> s.critical() || s.draining());
        int limit = urgent ? t.maxMovesUrgent() : t.maxMoves();

        Set<String> sourceIds = new HashSet<>();
        sources.forEach(s -> sourceIds.add(s.rack().id()));
        Map<String, Integer> reserved = new HashMap<>();
        Set<Long> planned = new HashSet<>();
        List<PlannedMove> moves = new ArrayList<>();

        // One work item per hot rack, plus one for the whole failed zone(s): draining orders jobs LOW first across
        // every rack of the zone, so a HIGH job on R21 never leaves before a LOW job on R22.
        record Work(Reason reason, boolean critical, double shed, List<Job> jobs) {
        }
        List<Work> work = new ArrayList<>();
        List<Job> drainJobs = new ArrayList<>();
        for (Source s : sources) {
            String src = s.rack().id();
            if (s.draining()) {
                drainJobs.addAll(movable(view.jobsOn(src), cd));
                continue;
            }
            if (!s.critical() && cd.sourceCoolingDown(src, false)) {
                continue;   // per-source cooldown, bypassed by CRITICAL
            }
            work.add(new Work(s.reason(), s.critical(), shedAmount(s.rack().forecast60C(), s.critical()),
                    movable(view.jobsOn(src), cd)));
        }
        if (!drainJobs.isEmpty()) {
            drainJobs.sort(MOVE_ORDER);
            // Draining goes right after CRITICAL racks and before WARNING racks.
            int at = (int) work.stream().filter(Work::critical).count();
            work.add(at, new Work(Reason.COOLING_FAILURE, false, Double.POSITIVE_INFINITY, drainJobs));
        }

        for (Work w : work) {
            double shedSoFar = 0;
            for (Job job : w.jobs()) {
                if (shedSoFar >= w.shed() || moves.size() >= limit) {
                    break;
                }
                if (planned.contains(job.id())) {
                    continue;
                }
                String src = job.rackId();
                Optional<Placement> dest = engine.place(job, view, reserved,
                        id -> id.equals(src) || sourceIds.contains(id) || cd.quarantined(id));
                if (dest.isEmpty()) {
                    continue;
                }
                Placement p = dest.get();
                moves.add(new PlannedMove(job, src, p.rackId(), w.reason(), p));
                reserved.merge(p.rackId(), job.cpu(), Integer::sum);
                planned.add(job.id());
                shedSoFar += job.cpu();
            }
        }
        return moves;
    }

    /**
     * Optional rebalance (plan 6D): when no rack is WARNING or CRITICAL and the standard deviation of rack utilisation
     * exceeds the threshold, move one LOW job (otherwise MEDIUM) from the most to the least utilised rack that passes
     * the destination rules.
     */
    public Optional<PlannedMove> planRebalance(ClusterView view, CooldownTracker cd) {
        if (view.mode() == Mode.HOLD || !view.faultZones().isEmpty()) {
            return Optional.empty();
        }
        List<Rack> active = view.racks().stream().filter(Rack::active).toList();
        if (active.size() < 2 || active.stream().anyMatch(r -> r.risk().atLeastWarning())) {
            return Optional.empty();
        }
        double[] util = active.stream().mapToDouble(r -> 100.0 * view.cpuUsed(r.id()) / r.capacity()).toArray();
        double mean = java.util.Arrays.stream(util).average().orElse(0);
        double sd = Math.sqrt(java.util.Arrays.stream(util).map(u -> (u - mean) * (u - mean)).average().orElse(0));
        if (sd <= t.rebalanceStdDevPct()) {
            return Optional.empty();
        }
        List<Rack> byUtil = new ArrayList<>(active);
        byUtil.sort(Comparator.comparingDouble((Rack r) -> -(double) view.cpuUsed(r.id()) / r.capacity())
                .thenComparing(Rack::id));
        Rack from = byUtil.get(0);
        for (Priority pr : new Priority[] {Priority.LOW, Priority.MEDIUM}) {
            List<Job> jobs = view.jobsOn(from.id()).stream()
                    .filter(j -> j.priority() == pr && !cd.workloadCoolingDown(j.id()) && !cd.failedRecently(j.id()))
                    .sorted(MOVE_ORDER).toList();
            for (Job job : jobs) {
                Optional<Placement> dest = engine.place(job, view, Map.of(),
                        id -> id.equals(from.id()) || cd.quarantined(id)
                                || view.cpuUsed(id) >= view.cpuUsed(from.id()));
                if (dest.isPresent()) {
                    Placement p = dest.get();
                    return Optional.of(new PlannedMove(job, from.id(), p.rackId(), Reason.REBALANCE, p));
                }
            }
        }
        return Optional.empty();
    }
}
