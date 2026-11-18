package com.thermogrid.scheduler.service;

import com.thermogrid.scheduler.config.SchedulerProperties;
import com.thermogrid.scheduler.domain.Migration;
import com.thermogrid.scheduler.domain.Repositories.MigrationRepository;
import com.thermogrid.scheduler.engine.CooldownTracker;
import com.thermogrid.scheduler.engine.MigrationPlanner;
import com.thermogrid.scheduler.engine.MigrationPlanner.PlannedMove;
import com.thermogrid.scheduler.engine.PlacementEngine;
import com.thermogrid.scheduler.engine.PlacementEngine.Placement;
import com.thermogrid.scheduler.model.Model.ClusterView;
import com.thermogrid.scheduler.model.Model.Job;
import com.thermogrid.scheduler.model.Model.Mode;
import com.thermogrid.scheduler.model.Model.Reason;
import com.thermogrid.scheduler.service.MigrationExecutor.Outcome;
import com.thermogrid.scheduler.snapshot.SnapshotSource;
import com.thermogrid.scheduler.snapshot.SnapshotUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduler's two loops and the manual rebalance. All cycles share one lock, so a workload can never be assigned
 * twice by overlapping cycles; a loop that finds the lock taken skips its run (the next run covers it).
 *
 * <ul>
 * <li>{@link #placePending()}, every 3 s: places PENDING workloads, HIGH priority first, always from a fresh view.
 * Runs whatever the autopilot flag says: the autopilot switch controls automatic migration only (plan 6D);</li>
 * <li>{@link #reconcile()}, every 5 s: re-reads state, repairs missed events and plans thermal migrations (not with the
 * autopilot off, not in HOLD). Pending jobs found are placed first; migration planning then waits for the next
 * cycle so it always starts from a fresh view.</li>
 * </ul>
 */
@Component
public class SchedulerCycles {

    private static final Logger log = LoggerFactory.getLogger(SchedulerCycles.class);

    /** Result of a manual rebalance request. */
    public record RebalanceResult(boolean moved, String message) {
    }

    private final SnapshotSource snapshots;
    private final PlacementEngine engine;
    private final MigrationPlanner planner;
    private final MigrationExecutor executor;
    private final MigrationRepository migrations;
    private final SchedulerState state;
    private final SchedulerProperties props;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public SchedulerCycles(SnapshotSource snapshots, PlacementEngine engine, MigrationPlanner planner,
                           MigrationExecutor executor, MigrationRepository migrations, SchedulerState state,
                           SchedulerProperties props, Clock clock) {
        this.snapshots = snapshots;
        this.engine = engine;
        this.planner = planner;
        this.executor = executor;
        this.migrations = migrations;
        this.state = state;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${thermogrid.scheduler.pending-ms}", initialDelayString = "${thermogrid.scheduler.pending-ms}")
    void scheduledPending() {
        placePending();
    }

    @Scheduled(fixedDelayString = "${thermogrid.scheduler.reconcile-ms}", initialDelayString = "${thermogrid.scheduler.reconcile-ms}")
    void scheduledReconcile() {
        reconcile();
    }

    /** @return number of workloads placed, or -1 if the cycle did not run (busy or state unavailable) */
    public int placePending() {
        if (!lock.tryLock()) {
            return -1;
        }
        try {
            ClusterView view = view();
            if (view == null) {
                return -1;
            }
            return placeFrom(view, tracker());
        } finally {
            lock.unlock();
        }
    }

    /** @return number of migrations applied, or -1 if the cycle did not run */
    public int reconcile() {
        if (!lock.tryLock()) {
            return -1;
        }
        try {
            ClusterView view = view();
            if (view == null) {
                return -1;
            }
            CooldownTracker tracker = tracker();
            state.cooldownRacks(tracker.cooldownRacks());
            int placed = placeFrom(view, tracker);
            int applied = 0;
            if (placed > 0) {
                log.debug("Reconcile placed {} pending workloads; migrations wait for a fresh view", placed);
            } else if (state.autopilot() && view.mode() == Mode.NORMAL) {
                List<PlannedMove> plan = planner.plan(view, tracker);
                if (!plan.isEmpty()) {
                    log.info("Migration plan: {}", plan.stream().map(m -> m.job().id() + " " + m.fromRackId()
                            + "->" + m.toRackId() + " " + m.reason()).toList());
                    applied = executor.executeAll(plan);
                }
            }
            state.lastCycleAt(clock.instant());
            return applied;
        } finally {
            lock.unlock();
        }
    }

    /** Operator-requested rebalance (plan 6D, optional). Allowed with the autopilot off, never in HOLD. */
    public RebalanceResult rebalanceNow() {
        lock.lock();
        try {
            ClusterView view = view();
            if (view == null) {
                return new RebalanceResult(false, "cluster state unavailable");
            }
            if (view.mode() == Mode.HOLD) {
                return new RebalanceResult(false, "HOLD mode: no thermal or balancing migrations");
            }
            Optional<PlannedMove> move = planner.planRebalance(view, tracker());
            if (move.isEmpty()) {
                return new RebalanceResult(false, "no rebalance needed");
            }
            Outcome o = executor.execute(move.get());
            return new RebalanceResult(o == Outcome.APPLIED, o == Outcome.APPLIED
                    ? "moved workload " + move.get().job().id() + " " + move.get().fromRackId() + " -> "
                    + move.get().toRackId() : "move failed: " + o);
        } finally {
            lock.unlock();
        }
    }

    /** Places the PENDING workloads of the view. Returns how many were applied. */
    private int placeFrom(ClusterView view, CooldownTracker tracker) {
        List<Job> pending = new ArrayList<>(view.pending());
        pending.sort(PlacementEngine.PENDING_ORDER);
        Map<String, Integer> reserved = new HashMap<>();
        int applied = 0;
        int noFit = 0;
        int backedOff = 0;
        for (Job job : pending) {
            if (tracker.failedRecently(job.id())) {
                backedOff++;
                continue;
            }
            Optional<Placement> p = engine.place(job, view, reserved, id -> false);
            if (p.isEmpty()) {
                noFit++;
                continue;
            }
            Outcome o = executor.execute(job, null, p.get().rackId(), Reason.INITIAL_PLACEMENT, p.get());
            if (o == Outcome.APPLIED) {
                applied++;
                reserved.merge(p.get().rackId(), job.cpu(), Integer::sum);
            } else if (o == Outcome.UNAVAILABLE) {
                break;
            }
        }
        state.pending(pending.size() - applied, noFit > 0);
        if (view.mode() == Mode.HOLD && applied > 0) {
            log.info("HOLD mode: placed {} workloads by CPU utilisation only", applied);
        }
        return applied;
    }

    private ClusterView view() {
        try {
            ClusterView v = snapshots.fetch();
            state.mode(v.mode());
            return v;
        } catch (SnapshotUnavailableException e) {
            log.warn("Cycle skipped: {}", e.getMessage());
            return null;
        }
    }

    CooldownTracker tracker() {
        Instant now = clock.instant();
        Duration horizon = Duration.ofSeconds(Math.max(props.tuning().workloadCooldownSec(),
                props.tuning().quarantineSec()));
        List<CooldownTracker.Move> applied = migrations
                .findByStatusAndCreatedAtAfter(Migration.APPLIED, now.minus(horizon)).stream()
                .map(m -> new CooldownTracker.Move(m.getWorkloadId(), m.getFromRackId(),
                        m.getAppliedAt() == null ? m.getCreatedAt() : m.getAppliedAt()))
                .toList();
        Map<Long, Instant> failed = new HashMap<>();
        migrations.findByStatusAndCreatedAtAfter(Migration.FAILED,
                        now.minusSeconds(props.tuning().failureBackoffSec()))
                .forEach(m -> failed.merge(m.getWorkloadId(), m.getCreatedAt(), (a, b) -> a.isAfter(b) ? a : b));
        return new CooldownTracker(now, props.tuning(), applied, failed, state.alertRacks());
    }
}
