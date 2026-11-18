package com.thermogrid.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.thermogrid.scheduler.config.Tuning;
import com.thermogrid.scheduler.engine.CooldownTracker;
import com.thermogrid.scheduler.engine.MigrationPlanner;
import com.thermogrid.scheduler.engine.MigrationPlanner.PlannedMove;
import com.thermogrid.scheduler.engine.PlacementEngine;
import com.thermogrid.scheduler.model.Model.ClusterView;
import com.thermogrid.scheduler.model.Model.Job;
import com.thermogrid.scheduler.model.Model.Mode;
import com.thermogrid.scheduler.model.Model.Priority;
import com.thermogrid.scheduler.model.Model.Rack;
import com.thermogrid.scheduler.model.Model.Reason;
import com.thermogrid.scheduler.model.Model.RiskLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure tests of plan section 6D. */
class MigrationPlannerTest {

    private static final Instant NOW = Instant.parse("2026-11-18T07:10:00Z");
    private final Tuning t = Tuning.defaults();
    private final MigrationPlanner planner = new MigrationPlanner(t, new PlacementEngine(t));

    private static Rack rack(String id, String zone, double forecast, RiskLevel risk) {
        return new Rack(id, zone, 100, true, forecast, forecast, risk, true);
    }

    private static Job job(long id, int cpu, Priority p, String rack) {
        return new Job(id, "w" + id, cpu, p, rack, 1);
    }

    private static ClusterView view(List<Rack> racks, Set<String> fault, List<Job> running) {
        return new ClusterView(NOW, Mode.NORMAL, racks, fault, running, List.of());
    }

    private CooldownTracker none() {
        return CooldownTracker.empty(NOW, t);
    }

    /** Z2 racks R21..R24 plus cool racks in Z1, Z3 and Z4 (R11..R14, R31..R34, R41..R44). */
    private List<Rack> grid(String hotId, double hotForecast, RiskLevel hotRisk) {
        List<Rack> racks = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 4; col++) {
                String id = "R" + row + col;
                racks.add(id.equals(hotId) ? rack(id, "Z" + row, hotForecast, hotRisk)
                        : rack(id, "Z" + row, 25.0, RiskLevel.NORMAL));
            }
        }
        return racks;
    }

    // ---- shed amount ----

    @Test
    void shedAmountFollowsTheFormula() {
        assertThat(planner.shedAmount(36.0, false)).isCloseTo(50.0, within(1e-9));       // (36 - 32) / 0.08
        assertThat(planner.shedAmount(34.0, false)).isCloseTo(25.0, within(1e-9));
        assertThat(planner.shedAmount(32.4, false)).isEqualTo(10.0);                      // floor of 10 units
        assertThat(planner.shedAmount(30.0, false)).isEqualTo(10.0);
        assertThat(planner.shedAmount(38.0, true)).isCloseTo(100.0, within(1e-9));       // CRITICAL reference 30.0
        assertThat(planner.shedAmount(33.0, true)).isCloseTo(37.5, within(1e-9));
        assertThat(planner.shedAmount(null, false)).isEqualTo(10.0);
    }

    @Test
    void movesStopOnceTheShedAmountIsReached() {
        // forecast 34.0 -> shed 25. Jobs LOW 30 first: one move covers it.
        List<Job> jobs = List.of(job(1, 30, Priority.LOW, "R23"), job(2, 20, Priority.LOW, "R23"),
                job(3, 10, Priority.MEDIUM, "R23"));
        List<PlannedMove> plan = planner.plan(view(grid("R23", 34.0, RiskLevel.WARNING), Set.of(), jobs), none());
        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).job().id()).isEqualTo(1L);
        assertThat(plan.get(0).reason()).isEqualTo(Reason.THERMAL_WARNING);
        assertThat(plan.get(0).fromRackId()).isEqualTo("R23");
        assertThat(plan.get(0).toRackId()).isNotEqualTo("R23");
    }

    @Test
    void largerShedMovesMoreJobsUpToTheLimit() {
        // forecast 36.0 -> shed 50. LOW 20 + LOW 20 + MEDIUM 20 would be needed, but the limit is 2 moves.
        List<Job> jobs = List.of(job(1, 20, Priority.LOW, "R23"), job(2, 20, Priority.LOW, "R23"),
                job(3, 20, Priority.MEDIUM, "R23"), job(4, 20, Priority.HIGH, "R23"));
        List<PlannedMove> plan = planner.plan(view(grid("R23", 36.0, RiskLevel.WARNING), Set.of(), jobs), none());
        assertThat(plan).hasSize(2);
        assertThat(plan).extracting(m -> m.job().id()).containsExactly(1L, 2L);
    }

    // ---- ordering ----

    @Test
    void lowBeforeMediumBeforeHighThenLargerCpuFirst() {
        List<Job> jobs = List.of(job(1, 10, Priority.HIGH, "R23"), job(2, 15, Priority.MEDIUM, "R23"),
                job(3, 20, Priority.LOW, "R23"), job(4, 30, Priority.LOW, "R23"), job(5, 25, Priority.MEDIUM, "R23"));
        // CRITICAL so the shed is large (forecast 40 -> 125 units) and the 4-move limit applies.
        List<PlannedMove> plan = planner.plan(view(grid("R23", 40.0, RiskLevel.CRITICAL), Set.of(), jobs), none());
        assertThat(plan).extracting(m -> m.job().id()).containsExactly(4L, 3L, 5L, 2L);
        assertThat(plan).allMatch(m -> m.reason() == Reason.THERMAL_CRITICAL);
    }

    // ---- limits ----

    @Test
    void warningAllowsTwoMovesCriticalAndDrainingAllowFour() {
        List<Job> many = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            many.add(job(i, 10, Priority.LOW, "R23"));
        }
        assertThat(planner.plan(view(grid("R23", 50.0, RiskLevel.WARNING), Set.of(), many), none())).hasSize(2);
        assertThat(planner.plan(view(grid("R23", 50.0, RiskLevel.CRITICAL), Set.of(), many), none())).hasSize(4);

        List<Job> zone2 = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            zone2.add(job(i, 10, Priority.LOW, "R21"));
            zone2.add(job(100 + i, 10, Priority.LOW, "R22"));
        }
        List<PlannedMove> drain = planner.plan(view(grid("none", 25.0, RiskLevel.NORMAL), Set.of("Z2"), zone2), none());
        assertThat(drain).hasSize(4);
        assertThat(drain).allMatch(m -> m.reason() == Reason.COOLING_FAILURE);
        assertThat(drain).allMatch(m -> !m.toRackId().startsWith("R2"));      // never into the failed zone
    }

    @Test
    void failedZoneIsDrainedCompletelyLowFirstEvenWithoutAnAlert() {
        List<Job> jobs = List.of(job(1, 10, Priority.HIGH, "R21"), job(2, 10, Priority.LOW, "R21"),
                job(3, 30, Priority.MEDIUM, "R21"));
        List<PlannedMove> plan = planner.plan(view(grid("none", 25.0, RiskLevel.NORMAL), Set.of("Z2"), jobs), none());
        assertThat(plan).extracting(m -> m.job().id()).containsExactly(2L, 3L, 1L);   // all three: no shed limit
    }

    @Test
    void drainingOrdersJobsLowFirstAcrossEveryRackOfTheFailedZone() {
        List<Job> jobs = List.of(job(1, 30, Priority.HIGH, "R21"), job(2, 10, Priority.LOW, "R22"),
                job(3, 20, Priority.MEDIUM, "R23"), job(4, 15, Priority.LOW, "R24"), job(5, 25, Priority.LOW, "R21"));
        List<PlannedMove> plan = planner.plan(view(grid("none", 25.0, RiskLevel.NORMAL), Set.of("Z2"), jobs), none());
        assertThat(plan).extracting(m -> m.job().id()).containsExactly(5L, 4L, 2L, 3L);   // limit 4, LOW 25, 15, 10, MED
        assertThat(plan).extracting(PlannedMove::fromRackId).containsExactly("R21", "R24", "R22", "R23");
    }

    // ---- cooldowns ----

    @Test
    void workloadMovedSixtySecondsAgoIsSkippedAndOneFromOneThirtyIsAllowed() {
        List<Job> jobs = List.of(job(1, 30, Priority.LOW, "R23"), job(2, 20, Priority.MEDIUM, "R23"));
        ClusterView v = view(grid("R23", 36.0, RiskLevel.WARNING), Set.of(), jobs);

        CooldownTracker recent = new CooldownTracker(NOW, t,
                List.of(new CooldownTracker.Move(1, "R99", NOW.minusSeconds(60))), Map.of(), Map.of());
        assertThat(planner.plan(v, recent)).extracting(m -> m.job().id()).containsExactly(2L);   // job 1 is skipped

        CooldownTracker old = new CooldownTracker(NOW, t,
                List.of(new CooldownTracker.Move(1, "R99", NOW.minusSeconds(130))), Map.of(), Map.of());
        assertThat(planner.plan(v, old)).extracting(m -> m.job().id()).containsExactly(1L, 2L);  // allowed again
    }

    @Test
    void sourceCooldownBlocksAWarningRackButNotACriticalOne() {
        List<Job> jobs = List.of(job(1, 30, Priority.LOW, "R23"));
        CooldownTracker cd = new CooldownTracker(NOW, t,
                List.of(new CooldownTracker.Move(77, "R23", NOW.minusSeconds(5))), Map.of(), Map.of());
        assertThat(planner.plan(view(grid("R23", 36.0, RiskLevel.WARNING), Set.of(), jobs), cd)).isEmpty();
        assertThat(planner.plan(view(grid("R23", 40.0, RiskLevel.CRITICAL), Set.of(), jobs), cd)).hasSize(1);
        // After 20 s the source cooldown has ended.
        CooldownTracker later = new CooldownTracker(NOW, t,
                List.of(new CooldownTracker.Move(77, "R23", NOW.minusSeconds(21))), Map.of(), Map.of());
        assertThat(planner.plan(view(grid("R23", 36.0, RiskLevel.WARNING), Set.of(), jobs), later)).hasSize(1);
    }

    @Test
    void failedWorkloadIsLeftAloneDuringTheBackoff() {
        List<Job> jobs = List.of(job(1, 30, Priority.LOW, "R23"), job(2, 30, Priority.LOW, "R23"));
        CooldownTracker cd = new CooldownTracker(NOW, t, List.of(), Map.of(1L, NOW.minusSeconds(3)), Map.of());
        assertThat(planner.plan(view(grid("R23", 34.0, RiskLevel.WARNING), Set.of(), jobs), cd))
                .extracting(m -> m.job().id()).containsExactly(2L);
    }

    @Test
    void sourcesAndAlertRacksAreNotDestinations() {
        // Everything except R11 and R12 is quarantined: both moves must land on those two.
        List<Rack> racks = new ArrayList<>(grid("R23", 36.0, RiskLevel.WARNING));
        Map<String, Instant> alerts = new java.util.HashMap<>();
        for (Rack r : racks) {
            if (!r.id().equals("R11") && !r.id().equals("R12") && !r.id().equals("R23")) {
                alerts.put(r.id(), NOW.minusSeconds(10));
            }
        }
        CooldownTracker cd = new CooldownTracker(NOW, t, List.of(), Map.of(), alerts);
        List<Job> jobs = List.of(job(1, 20, Priority.LOW, "R23"), job(2, 20, Priority.LOW, "R23"));
        List<PlannedMove> plan = planner.plan(view(racks, Set.of(), jobs), cd);
        assertThat(plan).extracting(PlannedMove::toRackId).allMatch(r -> r.equals("R11") || r.equals("R12"));
    }

    // ---- reservation ----

    @Test
    void capacityReservedForOneMoveIsNotOfferedToTheNext() {
        // The only possible destination is R11 (cap 85 of 100, 40 used -> 45 free). Two 30-unit jobs cannot both fit.
        List<Rack> racks = new ArrayList<>();
        racks.add(rack("R23", "Z2", 36.0, RiskLevel.WARNING));
        racks.add(rack("R11", "Z1", 25.0, RiskLevel.NORMAL));
        List<Job> jobs = List.of(job(1, 30, Priority.LOW, "R23"), job(2, 30, Priority.LOW, "R23"),
                job(9, 40, Priority.MEDIUM, "R11"));
        List<PlannedMove> plan = planner.plan(view(racks, Set.of(), jobs), none());
        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).toRackId()).isEqualTo("R11");
    }

    @Test
    void twoMovesInOneCycleSpreadWhenOneRackCannotTakeBoth() {
        List<Rack> racks = new ArrayList<>();
        racks.add(rack("R23", "Z2", 36.0, RiskLevel.WARNING));
        racks.add(rack("R11", "Z1", 25.0, RiskLevel.NORMAL));
        racks.add(rack("R12", "Z1", 25.5, RiskLevel.NORMAL));
        List<Job> jobs = List.of(job(1, 30, Priority.LOW, "R23"), job(2, 30, Priority.LOW, "R23"),
                job(8, 40, Priority.MEDIUM, "R11"), job(9, 40, Priority.MEDIUM, "R12"));
        List<PlannedMove> plan = planner.plan(view(racks, Set.of(), jobs), none());
        assertThat(plan).hasSize(2);
        assertThat(plan).extracting(PlannedMove::toRackId).containsExactlyInAnyOrder("R11", "R12");
    }

    // ---- guards ----

    @Test
    void holdModeNeverMigrates() {
        List<Job> jobs = List.of(job(1, 30, Priority.LOW, "R23"));
        ClusterView hold = new ClusterView(NOW, Mode.HOLD, grid("R23", 40.0, RiskLevel.CRITICAL), Set.of("Z3"), jobs,
                List.of());
        assertThat(planner.plan(hold, none())).isEmpty();
        assertThat(planner.planRebalance(hold, none())).isEmpty();
    }

    @Test
    void healthyClusterPlansNothing() {
        assertThat(planner.plan(view(grid("none", 25.0, RiskLevel.NORMAL), Set.of(),
                List.of(job(1, 30, Priority.LOW, "R23"))), none())).isEmpty();
    }

    @Test
    void staleSensorRackIsNotShedFromOnFrozenData() {
        List<Rack> racks = new ArrayList<>(grid("none", 25.0, RiskLevel.NORMAL));
        racks.replaceAll(r -> r.id().equals("R23") ? new Rack("R23", "Z2", 100, true, 36.0, 36.0, RiskLevel.WARNING,
                false) : r);
        assertThat(planner.plan(view(racks, Set.of(), List.of(job(1, 30, Priority.LOW, "R23"))), none())).isEmpty();
    }

    // ---- rebalance ----

    @Test
    void rebalanceMovesOneLowJobFromTheBusiestToTheIdlestRackWhenSpreadIsLarge() {
        List<Job> jobs = new ArrayList<>();
        jobs.add(job(1, 30, Priority.LOW, "R11"));
        jobs.add(job(2, 30, Priority.HIGH, "R11"));
        jobs.add(job(3, 20, Priority.LOW, "R11"));
        jobs.add(job(4, 25, Priority.MEDIUM, "R12"));
        jobs.add(job(5, 30, Priority.LOW, "R12"));
        jobs.add(job(6, 30, Priority.LOW, "R13"));
        List<Rack> racks = new ArrayList<>();
        for (int c = 1; c <= 4; c++) {
            racks.add(rack("R1" + c, "Z1", 25.0, RiskLevel.NORMAL));
        }
        // Utilisations 80, 55, 30, 0 -> standard deviation about 29 points.
        var move = planner.planRebalance(view(racks, Set.of(), jobs), none());
        assertThat(move).isPresent();
        assertThat(move.get().reason()).isEqualTo(Reason.REBALANCE);
        assertThat(move.get().fromRackId()).isEqualTo("R11");
        assertThat(move.get().job().priority()).isEqualTo(Priority.LOW);
        assertThat(move.get().job().id()).isEqualTo(1L);          // larger LOW job first
    }

    @Test
    void rebalanceDoesNothingWhenBalancedOrWhenAnAlertIsActive() {
        List<Rack> racks = List.of(rack("R11", "Z1", 25.0, RiskLevel.NORMAL), rack("R12", "Z1", 25.0, RiskLevel.NORMAL));
        List<Job> balanced = List.of(job(1, 30, Priority.LOW, "R11"), job(2, 40, Priority.LOW, "R12"));
        assertThat(planner.planRebalance(view(racks, Set.of(), balanced), none())).isEmpty();

        List<Rack> withWarning = List.of(rack("R11", "Z1", 25.0, RiskLevel.NORMAL),
                rack("R12", "Z1", 33.0, RiskLevel.WARNING));
        List<Job> skewed = List.of(job(1, 80, Priority.LOW, "R11"));
        assertThat(planner.planRebalance(view(withWarning, Set.of(), skewed), none())).isEmpty();
    }
}
