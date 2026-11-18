package com.thermogrid.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.thermogrid.scheduler.config.Tuning;
import com.thermogrid.scheduler.engine.PlacementEngine;
import com.thermogrid.scheduler.engine.PlacementEngine.Placement;
import com.thermogrid.scheduler.model.Model.ClusterView;
import com.thermogrid.scheduler.model.Model.Job;
import com.thermogrid.scheduler.model.Model.Mode;
import com.thermogrid.scheduler.model.Model.Priority;
import com.thermogrid.scheduler.model.Model.Rack;
import com.thermogrid.scheduler.model.Model.RiskLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure tests of plan section 6C. No Spring, no clock, no I/O. */
class PlacementEngineTest {

    private final PlacementEngine engine = new PlacementEngine(Tuning.defaults());
    private static final Instant NOW = Instant.parse("2026-11-18T07:10:00Z");

    static Rack rack(String id, String zone, double forecast) {
        return new Rack(id, zone, 100, true, forecast, forecast, RiskLevel.NORMAL, true);
    }

    static Job job(long id, int cpu, Priority p, String rack) {
        return new Job(id, "w" + id, cpu, p, rack, 0);
    }

    static ClusterView view(Mode mode, List<Rack> racks, Set<String> fault, List<Job> running, List<Job> pending) {
        return new ClusterView(NOW, mode, racks, fault, running, pending);
    }

    /** Filler so a zone reaches a chosen total utilisation: units spread over the zone's other racks. */
    static List<Job> filler(long firstId, String rackId, int cpu) {
        List<Job> out = new ArrayList<>();
        long id = firstId;
        int left = cpu;
        while (left > 0) {
            int c = Math.min(30, left);
            out.add(job(id++, c, Priority.MEDIUM, rackId));
            left -= c;
        }
        return out;
    }

    // ---- the worked example of section 6C ----

    /**
     * R41: forecast 25.0, 40 units used, Z4 utilisation 0.50. R31: forecast 27.0, 20 units used, Z3 utilisation 0.45.
     * 20-unit job. Scores 0.428 and 0.423 (gap 0.005 < 0.02), so the tighter rack R41 wins.
     */
    private ClusterView workedExample() {
        List<Rack> racks = List.of(
                rack("R41", "Z4", 25.0), rack("R42", "Z4", 40.0), rack("R43", "Z4", 40.0), rack("R44", "Z4", 40.0),
                rack("R31", "Z3", 27.0), rack("R32", "Z3", 40.0), rack("R33", "Z3", 40.0), rack("R34", "Z3", 40.0));
        List<Job> running = new ArrayList<>();
        running.add(job(1, 30, Priority.MEDIUM, "R41"));
        running.add(job(2, 10, Priority.MEDIUM, "R41"));                    // R41: 40 used
        running.addAll(filler(10, "R42", 160));                              // Z4 total 200 of 400 = 0.50
        running.add(job(3, 20, Priority.MEDIUM, "R31"));                    // R31: 20 used
        running.addAll(filler(30, "R32", 160));                              // Z3 total 180 of 400 = 0.45
        // keep the other racks out of the race: they are hot and full enough to score worse or fail filters
        return view(Mode.NORMAL, racks, Set.of(), running, List.of());
    }

    @Test
    void workedExampleChoosesR41OverR31() {
        ClusterView v = workedExample();
        Job candidate = job(99, 20, Priority.LOW, null);
        // Remove the other racks from consideration so the two named racks are the top two.
        Optional<Placement> p = engine.place(candidate, v, Map.of(),
                id -> !id.equals("R41") && !id.equals("R31"));
        assertThat(p).isPresent();
        Placement placement = p.get();
        assertThat(placement.top3()).extracting(c -> c.rackId()).containsExactly("R31", "R41");
        assertThat(placement.top3().get(0).score()).isEqualTo(0.423, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(placement.top3().get(1).score()).isEqualTo(0.428, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(placement.top3().get(1).score() - placement.top3().get(0).score()).isLessThan(0.02);
        assertThat(placement.top3().get(1).tAfterC()).isEqualTo(26.6, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(placement.rackId()).isEqualTo("R41");            // best-fit tie-break
        assertThat(placement.score()).isEqualTo(0.428, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(placement.mode()).isEqualTo(Mode.NORMAL);
    }

    @Test
    void gapOfTwoHundredthsOrMoreKeepsTheLowestScore() {
        // Same racks, but R31 is much cooler: gap >= 0.02, so the lowest score wins even though R41 is tighter.
        ClusterView v = workedExample();
        List<Rack> racks = new ArrayList<>(v.racks());
        racks.replaceAll(r -> r.id().equals("R31") ? rack("R31", "Z3", 24.0) : r);
        ClusterView cooler = view(Mode.NORMAL, racks, Set.of(), v.running(), List.of());
        Placement p = engine.place(job(99, 20, Priority.LOW, null), cooler, Map.of(),
                id -> !id.equals("R41") && !id.equals("R31")).orElseThrow();
        assertThat(p.rackId()).isEqualTo("R31");
    }

    // ---- priority order ----

    @Test
    void pendingJobsAreOrderedHighFirstThenLargestCpu() {
        List<Job> jobs = new ArrayList<>(List.of(
                job(1, 10, Priority.LOW, null), job(2, 30, Priority.MEDIUM, null), job(3, 20, Priority.HIGH, null),
                job(4, 25, Priority.HIGH, null), job(5, 30, Priority.LOW, null), job(6, 15, Priority.MEDIUM, null)));
        jobs.sort(PlacementEngine.PENDING_ORDER);
        assertThat(jobs).extracting(Job::id).containsExactly(4L, 3L, 2L, 6L, 5L, 1L);
    }

    // ---- filters ----

    private ClusterView threeRacks(Rack a, Rack b, Rack c, Set<String> fault, List<Job> running) {
        return view(Mode.NORMAL, List.of(a, b, c), fault, running, List.of());
    }

    @Test
    void capacityAboveEightyFivePercentIsRejected() {
        Rack r = rack("R11", "Z1", 25.0);
        // 70 used + 15 = 85: allowed (at the cap). 70 + 16 = 86: rejected.
        ClusterView v = view(Mode.NORMAL, List.of(r), Set.of(), List.of(job(1, 70, Priority.LOW, "R11")), List.of());
        assertThat(engine.place(job(9, 15, Priority.LOW, null), v, Map.of(), x -> false)).isPresent();
        assertThat(engine.place(job(9, 16, Priority.LOW, null), v, Map.of(), x -> false)).isEmpty();
    }

    @Test
    void staleRackFaultZoneMaintenanceWarningAndThermalHeadroomAreAllExcluded() {
        Rack stale = new Rack("R11", "Z1", 100, true, 24.0, 24.0, RiskLevel.NORMAL, false);
        Rack inFault = rack("R21", "Z2", 24.0);
        Rack maintenance = new Rack("R31", "Z3", 100, false, 24.0, 24.0, RiskLevel.NORMAL, true);
        Rack warning = new Rack("R41", "Z4", 100, true, 24.0, 24.0, RiskLevel.WARNING, true);
        Rack tooHot = rack("R42", "Z4", 29.0);               // 29 + 0.08 * 40 = 32.2 >= 32
        Rack ok = rack("R43", "Z4", 28.0);                   // 28 + 3.2 = 31.2 < 32
        ClusterView v = view(Mode.NORMAL, List.of(stale, inFault, maintenance, warning, tooHot, ok), Set.of("Z2"),
                List.of(), List.of());
        Placement p = engine.place(job(1, 40, Priority.LOW, null), v, Map.of(), x -> false).orElseThrow();
        assertThat(p.top3()).extracting(c -> c.rackId()).containsExactly("R43");
        assertThat(p.rackId()).isEqualTo("R43");
    }

    @Test
    void nothingFitsMeansNoPlacement() {
        Rack hot = rack("R11", "Z1", 31.9);
        ClusterView v = view(Mode.NORMAL, List.of(hot), Set.of(), List.of(), List.of());
        assertThat(engine.place(job(1, 20, Priority.LOW, null), v, Map.of(), x -> false)).isEmpty();
    }

    @Test
    void reservedCapacityIsNotOfferedTwice() {
        Rack r = rack("R11", "Z1", 25.0);
        ClusterView v = view(Mode.NORMAL, List.of(r), Set.of(), List.of(), List.of());
        Job j = job(1, 50, Priority.LOW, null);
        assertThat(engine.place(j, v, Map.of(), x -> false)).isPresent();
        assertThat(engine.place(j, v, Map.of("R11", 50), x -> false)).isEmpty();     // 50 + 50 > 85
        assertThat(engine.place(job(2, 30, Priority.LOW, null), v, Map.of("R11", 50), x -> false)).isPresent();
    }

    @Test
    void excludedRacksAreNeverChosen() {
        ClusterView v = threeRacks(rack("R11", "Z1", 22.0), rack("R12", "Z1", 28.0), rack("R13", "Z1", 28.5),
                Set.of(), List.of());
        Placement p = engine.place(job(1, 10, Priority.LOW, null), v, Map.of(), id -> id.equals("R11")).orElseThrow();
        assertThat(p.rackId()).isNotEqualTo("R11");
    }

    @Test
    void topThreeAreSavedBestFirst() {
        ClusterView v = view(Mode.NORMAL, List.of(rack("R11", "Z1", 28.0), rack("R12", "Z1", 22.0),
                rack("R13", "Z1", 25.0), rack("R14", "Z1", 24.0)), Set.of(), List.of(), List.of());
        Placement p = engine.place(job(1, 10, Priority.LOW, null), v, Map.of(), x -> false).orElseThrow();
        assertThat(p.top3()).hasSize(3);
        assertThat(p.top3()).isSortedAccordingTo(Comparator.comparingDouble(c -> c.score()));
        assertThat(p.top3()).extracting(c -> c.rackId()).containsExactly("R12", "R14", "R13");
    }

    // ---- HOLD ----

    @Test
    void holdModeScoresOnUtilisationOnlyAndIgnoresTemperature() {
        // R11 is "hotter" and has no sensor, but telemetry is gone so only CPU matters. R12 has more load.
        Rack a = new Rack("R11", "Z1", 100, true, null, null, RiskLevel.NORMAL, true);
        Rack b = new Rack("R12", "Z1", 100, true, null, null, RiskLevel.NORMAL, true);
        ClusterView v = view(Mode.HOLD, List.of(a, b), Set.of(), List.of(job(1, 40, Priority.LOW, "R12")), List.of());
        Placement p = engine.place(job(2, 10, Priority.LOW, null), v, Map.of(), x -> false).orElseThrow();
        assertThat(p.rackId()).isEqualTo("R11");
        assertThat(p.mode()).isEqualTo(Mode.HOLD);
        assertThat(p.score()).isEqualTo(0.10, org.assertj.core.api.Assertions.within(1e-9));   // utilAfter only
    }

    @Test
    void holdModeCapsUtilisationAtSeventyPercentAndStillAvoidsFaultZones() {
        Rack a = new Rack("R11", "Z1", 100, true, null, null, RiskLevel.NORMAL, true);
        Rack faulty = new Rack("R21", "Z2", 100, true, null, null, RiskLevel.NORMAL, true);
        ClusterView v = view(Mode.HOLD, List.of(a, faulty), Set.of("Z2"), List.of(job(1, 60, Priority.LOW, "R11")),
                List.of());
        assertThat(engine.place(job(2, 10, Priority.LOW, null), v, Map.of(), x -> false)).isPresent();    // 70 = cap
        assertThat(engine.place(job(2, 11, Priority.LOW, null), v, Map.of(), x -> false)).isEmpty();      // 71 > 70
        // Z2 is empty and would fit 11 units, but it is FAULT.
    }

    @Test
    void sameScoresAreResolvedDeterministicallyByRackId() {
        ClusterView v = view(Mode.NORMAL, List.of(rack("R12", "Z1", 25.0), rack("R11", "Z1", 25.0)), Set.of(),
                List.of(), List.of());
        Placement p = engine.place(job(1, 10, Priority.LOW, null), v, Map.of(), x -> false).orElseThrow();
        assertThat(p.rackId()).isEqualTo("R11");
    }
}
