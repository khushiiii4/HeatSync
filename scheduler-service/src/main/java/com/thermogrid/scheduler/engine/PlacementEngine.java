package com.thermogrid.scheduler.engine;

import com.thermogrid.scheduler.config.Tuning;
import com.thermogrid.scheduler.model.Model.ClusterView;
import com.thermogrid.scheduler.model.Model.Job;
import com.thermogrid.scheduler.model.Model.Mode;
import com.thermogrid.scheduler.model.Model.Rack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Thermal-aware best-fit placement (plan section 6C). Pure: no clock, no I/O, no state.
 *
 * <pre>
 * filters: rack ACTIVE, zone cooling not FAULT, sensor OK, risk below WARNING,
 *          cpuUsed + job.cpu &lt;= 85% of capacity, forecast60 + 0.08 * job.cpu &lt; 32 C
 * Tafter    = forecast60 + 0.08 * job.cpu
 * utilAfter = (cpuUsed + job.cpu) / capacity
 * score     = 0.6 * (Tafter - 20) / 20 + 0.3 * utilAfter + 0.1 * zoneUtilization     (lowest wins)
 * </pre>
 * If the two best scores differ by less than 0.02 the rack with less remaining capacity wins. In HOLD mode (no
 * telemetry) only utilAfter is scored, utilisation is capped at 70 percent and FAULT zones are still avoided.
 */
public class PlacementEngine {

    /** One scored candidate rack. {@code tAfterC} is NaN in HOLD mode. */
    public record Candidate(String rackId, double score, double tAfterC, double utilAfter) {
    }

    /** The chosen rack, its score and the best (up to three) candidates, best first, for explanations. */
    public record Placement(String rackId, double score, Mode mode, List<Candidate> top3) {
    }

    private final Tuning t;

    public PlacementEngine(Tuning tuning) {
        this.t = tuning;
    }

    /**
     * @param reserved CPU units already promised to racks earlier in the same cycle (never offered twice)
     * @param excluded racks that may not be chosen (the source rack, quarantined racks, ...)
     */
    public Optional<Placement> place(Job job, ClusterView view, Map<String, Integer> reserved,
                                     Predicate<String> excluded) {
        boolean hold = view.mode() == Mode.HOLD;
        double cap = hold ? t.holdCapacityCap() : t.capacityCap();
        List<Candidate> candidates = new ArrayList<>();
        for (Rack r : view.racks()) {
            if (!r.active() || excluded.test(r.id()) || view.faultZones().contains(r.zoneId())) {
                continue;
            }
            int usedAfter = view.cpuUsed(r.id()) + reserved.getOrDefault(r.id(), 0) + job.cpu();
            double utilAfter = (double) usedAfter / r.capacity();
            if (usedAfter > cap * r.capacity() + 1e-9) {
                continue;
            }
            if (hold) {
                candidates.add(new Candidate(r.id(), utilAfter, Double.NaN, utilAfter));
                continue;
            }
            if (!r.sensorOk() || r.forecast60C() == null || r.risk().atLeastWarning()) {
                continue;
            }
            double tAfter = r.forecast60C() + t.heatPerCpu() * job.cpu();
            if (tAfter >= t.maxAfterC()) {
                continue;
            }
            double score = t.wTemp() * (tAfter - t.tempRefC()) / t.tempSpanC() + t.wUtil() * utilAfter
                    + t.wZone() * view.zoneUtilization(r.zoneId());
            candidates.add(new Candidate(r.id(), score, tAfter, utilAfter));
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).thenComparing(Candidate::rackId));
        Candidate best = candidates.get(0);
        if (candidates.size() > 1) {
            Candidate second = candidates.get(1);
            if (second.score() - best.score() < t.tieGap() && remaining(second, view, reserved, job)
                    < remaining(best, view, reserved, job)) {
                best = second;
            }
        }
        return Optional.of(new Placement(best.rackId(), best.score(), view.mode(),
                List.copyOf(candidates.subList(0, Math.min(3, candidates.size())))));
    }

    /** CPU units left on the rack after the job lands (smaller means a tighter, best-fit choice). */
    private static double remaining(Candidate c, ClusterView view, Map<String, Integer> reserved, Job job) {
        Rack r = view.racks().stream().filter(x -> x.id().equals(c.rackId())).findFirst().orElseThrow();
        return r.capacity() - view.cpuUsed(r.id()) - reserved.getOrDefault(r.id(), 0) - job.cpu();
    }

    /** HIGH priority first, then CPU demand descending, then id (deterministic). */
    public static final Comparator<Job> PENDING_ORDER = Comparator
            .comparing((Job j) -> j.priority()).reversed()
            .thenComparing(Comparator.comparingInt(Job::cpu).reversed())
            .thenComparingLong(Job::id);
}
