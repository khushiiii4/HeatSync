package com.thermogrid.simulator;

import static com.thermogrid.simulator.TestTopology.INITIAL;
import static com.thermogrid.simulator.TestTopology.PLAN;
import static com.thermogrid.simulator.TestTopology.PLAN_NO_NOISE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.thermogrid.simulator.physics.PhysicsModel;
import com.thermogrid.simulator.physics.RackSpec;
import com.thermogrid.simulator.sim.SimState;
import com.thermogrid.simulator.sim.SimState.RackState;
import com.thermogrid.simulator.sim.SimState.Snapshot;
import com.thermogrid.simulator.sim.SimulationEngine;
import com.thermogrid.simulator.topology.RackGrid;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SimulationEngineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T07:10:06.310Z"), ZoneOffset.UTC);

    private record Rig(RackGrid grid, SimState state, SimulationEngine engine) {
    }

    private static Rig rig(RackGrid grid, PhysicsModel model) {
        SimState state = new SimState(grid, model, INITIAL, CLOCK);
        return new Rig(grid, state, new SimulationEngine(grid, model, state, CLOCK));
    }

    private static Rig rig(RackGrid grid) {
        return rig(grid, new PhysicsModel(PLAN_NO_NOISE, () -> 0.0));
    }

    /** Uniform 26 C except R22 at 40 C and R14 at 20 C, so neighbour effects are visible. */
    private static void seedUneven(Rig rig) {
        Snapshot s = rig.state().snapshot();
        Map<String, RackState> racks = new LinkedHashMap<>(s.racks());
        racks.put("R22", new RackState(40.0, 60, 6.8, 0));
        racks.put("R14", new RackState(20.0, 60, 6.8, 0));
        rig.state().replace(new Snapshot(s.tick(), s.timestamp(), racks, s.zones()));
    }

    @Test
    void initialStateIsTickZeroWithConfiguredValues() {
        Rig r = rig(TestTopology.grid());
        Snapshot s = r.state().snapshot();
        assertThat(s.tick()).isZero();
        assertThat(s.timestamp()).isEqualTo(CLOCK.instant());
        assertThat(r.state().isPaused()).isFalse();
        assertThat(s.racks()).hasSize(16).allSatisfy((id, st) -> {
            assertThat(st.tempC()).isEqualTo(26.0);
            assertThat(st.cpuUnits()).isEqualTo(60.0);
            assertThat(st.powerKw()).isCloseTo(6.8, within(1e-12));
        });
        assertThat(s.zones()).hasSize(4).allSatisfy((id, z) -> {
            assertThat(z.outputPct()).isEqualTo(40.0);
            assertThat(z.health().name()).isEqualTo("OK");
        });
    }

    @Test
    void oneTickUpdatesEveryActiveRackAndMatchesIndependentCalculation() {
        Rig r = rig(TestTopology.grid());
        seedUneven(r);
        Snapshot before = r.state().snapshot();

        assertThat(r.engine().tick()).isTrue();
        Snapshot after = r.state().snapshot();

        for (RackSpec rack : r.grid().racks()) {
            double t = before.racks().get(rack.id()).tempC();
            List<RackSpec> nb = r.grid().neighboursOf(rack.id());
            double mean = nb.stream().mapToDouble(n -> before.racks().get(n.id()).tempC()).average().orElseThrow();
            double expected = t + 0.06 * 6.8 - 0.12 * 0.40 * (t - 18.0) + 0.05 * (mean - t);
            assertThat(after.racks().get(rack.id()).tempC()).as(rack.id()).isCloseTo(expected, within(1e-12));
            assertThat(after.racks().get(rack.id()).tempC()).as(rack.id() + " changed").isNotEqualTo(t);
        }
    }

    @Test
    void cornerEdgeAndInnerRacksUseOnlyExistingNeighbours() {
        RackGrid g = TestTopology.grid();
        assertThat(ids(g, "R11")).containsExactlyInAnyOrder("R21", "R12");
        assertThat(ids(g, "R44")).containsExactlyInAnyOrder("R34", "R43");
        assertThat(ids(g, "R14")).containsExactlyInAnyOrder("R24", "R13");
        assertThat(ids(g, "R12")).containsExactlyInAnyOrder("R11", "R13", "R22");
        assertThat(ids(g, "R41")).containsExactlyInAnyOrder("R31", "R42");
        assertThat(ids(g, "R22")).containsExactlyInAnyOrder("R12", "R32", "R21", "R23");
        // Diagonals are never neighbours.
        assertThat(ids(g, "R22")).doesNotContain("R11", "R33", "R13", "R31");
    }

    @Test
    void cornerRackCouplingAveragesOnlyItsTwoNeighbours() {
        Rig r = rig(TestTopology.grid());
        Snapshot s = r.state().snapshot();
        Map<String, RackState> racks = new LinkedHashMap<>(s.racks());
        racks.put("R21", new RackState(30.0, 60, 6.8, 0));   // neighbour of R11
        racks.put("R12", new RackState(34.0, 60, 6.8, 0));   // neighbour of R11
        racks.put("R22", new RackState(90.0, 60, 6.8, 0));   // diagonal of R11, must be ignored
        r.state().replace(new Snapshot(0, s.timestamp(), racks, s.zones()));

        r.engine().tick();

        double expected = 26.0 + 0.06 * 6.8 - 0.12 * 0.4 * 8.0 + 0.05 * (32.0 - 26.0);
        assertThat(r.state().snapshot().racks().get("R11").tempC()).isCloseTo(expected, within(1e-12));
    }

    @Test
    void neighbourTemperaturesComeFromThePreviousTickNotPartialUpdates() {
        // R22 is hot. R12/R21 are visited before and R23/R32 after it in row-major order. If any rack saw R22's
        // already-updated value, one of the four would differ from the previous-tick formula.
        Rig r = rig(TestTopology.grid());
        seedUneven(r);
        r.engine().tick();
        Snapshot after = r.state().snapshot();
        for (String id : List.of("R12", "R21", "R23", "R32")) {
            double t = 26.0;
            double mean = r.grid().neighboursOf(id).stream()
                    .mapToDouble(n -> n.id().equals("R22") ? 40.0 : (n.id().equals("R14") ? 20.0 : 26.0))
                    .average().orElseThrow();
            double expected = t + 0.06 * 6.8 - 0.12 * 0.4 * (t - 18.0) + 0.05 * (mean - t);
            assertThat(after.racks().get(id).tempC()).as(id).isCloseTo(expected, within(1e-12));
        }
    }

    @Test
    void rackUpdateOrderDoesNotAffectResults() {
        RackGrid forward = TestTopology.grid();
        List<RackSpec> reversed = new ArrayList<>(forward.racks());
        Collections.reverse(reversed);
        List<RackSpec> shuffled = new ArrayList<>(forward.racks());
        Collections.shuffle(shuffled, new Random(3));

        Rig a = rig(forward);
        Rig b = rig(forward.withRackOrder(reversed));
        Rig c = rig(forward.withRackOrder(shuffled));
        for (Rig r : List.of(a, b, c)) {
            seedUneven(r);
        }
        for (int i = 0; i < 25; i++) {
            a.engine().tick();
            b.engine().tick();
            c.engine().tick();
        }
        for (RackSpec rack : forward.racks()) {
            double ta = a.state().snapshot().racks().get(rack.id()).tempC();
            assertThat(b.state().snapshot().racks().get(rack.id()).tempC()).as(rack.id()).isEqualTo(ta);
            assertThat(c.state().snapshot().racks().get(rack.id()).tempC()).as(rack.id()).isEqualTo(ta);
        }
    }

    @Test
    void maintenanceRacksAreExcludedFromPhysicsAndReadings() {
        Rig r = rig(TestTopology.grid(Set.of("R22")));
        Snapshot before = r.state().snapshot();
        assertThat(before.racks().get("R22").cpuUnits()).isZero();
        assertThat(before.racks().get("R22").powerKw()).isZero();

        r.engine().tick();
        Snapshot after = r.state().snapshot();

        assertThat(after.racks().get("R22")).isEqualTo(before.racks().get("R22"));
        assertThat(after.racks().get("R11").tempC()).isNotEqualTo(26.0);
        // R12 still averages R22's frozen 26.0 among its neighbours.
        double expected = 26.0 + 0.06 * 6.8 - 0.12 * 0.4 * 8.0;
        assertThat(after.racks().get("R12").tempC()).isCloseTo(expected, within(1e-12));
    }

    @Test
    void pausedEngineDoesNotAdvanceAndResumeContinues() {
        Rig r = rig(TestTopology.grid());
        r.engine().tick();
        Snapshot before = r.state().snapshot();

        r.engine().pause();
        assertThat(r.engine().tick()).isFalse();
        assertThat(r.engine().tick()).isFalse();
        assertThat(r.state().snapshot()).isSameAs(before);
        assertThat(r.state().isPaused()).isTrue();

        r.engine().resume();
        assertThat(r.engine().tick()).isTrue();
        assertThat(r.state().snapshot().tick()).isEqualTo(2);
    }

    @Test
    void tickCounterIncrementsExactlyOncePerSuccessfulTick() {
        Rig r = rig(TestTopology.grid());
        for (int i = 1; i <= 7; i++) {
            r.engine().tick();
            assertThat(r.state().snapshot().tick()).isEqualTo(i);
        }
    }

    @Test
    void failedTickLeavesStateAndCounterUntouched() {
        int[] calls = {0};
        PhysicsModel flaky = new PhysicsModel(PLAN, () -> {
            if (++calls[0] == 10) {
                throw new IllegalStateException("boom");
            }
            return 0.0;
        });
        Rig r = rig(TestTopology.grid(), flaky);
        Snapshot before = r.state().snapshot();

        assertThatThrownBy(() -> r.engine().tick()).isInstanceOf(IllegalStateException.class);

        assertThat(r.state().snapshot()).isSameAs(before);
        assertThat(r.state().snapshot().tick()).isZero();
        assertThat(r.engine().tick()).isTrue();
        assertThat(r.state().snapshot().tick()).isEqualTo(1);
    }

    @Test
    void resetReturnsToInitialValuesAtTickZero() {
        Rig r = rig(TestTopology.grid());
        for (int i = 0; i < 5; i++) {
            r.engine().tick();
        }
        r.engine().reset();
        assertThat(r.state().snapshot().tick()).isZero();
        assertThat(r.state().snapshot().racks().values()).allSatisfy(st -> assertThat(st.tempC()).isEqualTo(26.0));
    }

    @Test
    void snapshotMapsCannotBeMutatedByReaders() {
        Snapshot s = rig(TestTopology.grid()).state().snapshot();
        assertThatThrownBy(() -> s.racks().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> s.zones().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void uniformSixtyUnitsAtFortyPercentSettlesAtTheSteadyStateFormula() {
        // Uniform load means neighbour coupling cancels out at steady state.
        Rig r = rig(TestTopology.grid());
        for (int i = 0; i < 300; i++) {
            r.engine().tick();
        }
        double steady = 18.0 + 0.06 * 6.8 / (0.12 * 0.4);
        r.state().snapshot().racks().values()
                .forEach(st -> assertThat(st.tempC()).isCloseTo(steady, within(0.1)));
    }

    private static List<String> ids(RackGrid g, String id) {
        return g.neighboursOf(id).stream().map(RackSpec::id).toList();
    }
}
