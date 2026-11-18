package com.thermogrid.simulator;

import static com.thermogrid.simulator.TestTopology.INITIAL;
import static com.thermogrid.simulator.TestTopology.PLAN_NO_NOISE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.thermogrid.simulator.TestSupport.MutableClock;
import com.thermogrid.simulator.config.SimulatorProperties;
import com.thermogrid.simulator.failure.FailureRecord;
import com.thermogrid.simulator.failure.FailureRegistry;
import com.thermogrid.simulator.failure.FailureRepository;
import com.thermogrid.simulator.failure.FailureType;
import com.thermogrid.simulator.physics.HealthState;
import com.thermogrid.simulator.physics.PhysicsModel;
import com.thermogrid.simulator.sim.SimState;
import com.thermogrid.simulator.sim.SimState.Snapshot;
import com.thermogrid.simulator.sim.SimulationEngine;
import com.thermogrid.simulator.sim.TickPublisher;
import com.thermogrid.simulator.topology.RackGrid;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How each failure type turns into model inputs and publication decisions. The physics formula is untouched. */
class EngineFailureTest {

    private static final Instant T0 = Instant.parse("2026-11-18T12:45:00Z");

    private record Published(long tick, Set<String> dropped) {
    }

    private MutableClock clock;
    private FailureRegistry registry;
    private FailureRepository repo;
    private SimState state;
    private SimulationEngine engine;
    private final List<Published> published = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        repo = new FailureRepository(TestSupport.memoryDb());
        RackGrid grid = TestTopology.grid();
        SimulatorProperties props = new SimulatorProperties(2000, false, null, TestTopology.GRID, INITIAL,
                new SimulatorProperties.Physics(18.0, 0.06, 0.12, 0.05, 0.0, 0.10,
                        new SimulatorProperties.HealthFactors(1.0, 0.25, 0.0)),
                new SimulatorProperties.Publishing(false, "SIMULATOR-SERVICE", 1, 10000),
                new SimulatorProperties.Failures(86400, 50.0), new SimulatorProperties.Bootstrap(1, 1, 1));
        registry = new FailureRegistry(repo, grid, props, clock);
        PhysicsModel model = new PhysicsModel(PLAN_NO_NOISE, () -> 0.0);
        state = new SimState(grid, model, INITIAL, clock);
        TickPublisher recorder = (s, dropped) -> published.add(new Published(s.tick(), Set.copyOf(dropped)));
        engine = new SimulationEngine(grid, model, state, clock, registry, recorder);
    }

    private double temp(String rack) {
        return state.snapshot().racks().get(rack).tempC();
    }

    private void tick() {
        clock.advance(Duration.ofSeconds(2));
        engine.tick();
    }

    @Test
    void withoutFailuresZonesStayOkAndNothingIsDropped() {
        tick();
        assertThat(state.snapshot().zones().values()).allSatisfy(z -> {
            assertThat(z.health()).isEqualTo(HealthState.OK);
            assertThat(z.healthFactorOverride()).isNull();
        });
        assertThat(published).containsExactly(new Published(1, Set.of()));
    }

    @Test
    void coolingFailureMakesTheZoneFaultAndItsRacksHeatFast() {
        registry.inject(FailureType.COOLING_FAILURE, "Z2", 0, null, "operator");
        double before = temp("R23");
        tick();

        assertThat(state.snapshot().zones().get("Z2").health()).isEqualTo(HealthState.FAULT);
        assertThat(state.snapshot().zones().get("Z1").health()).isEqualTo(HealthState.OK);
        // f falls to the 0.10 floor: dT = 0.06*6.8 - 0.12*0.10*(26-18) + coupling(0) = 0.312
        assertThat(temp("R23") - before).isCloseTo(0.312, within(1e-9)).isGreaterThan(0.2);
        // A rack in a healthy zone cools normally (26 -> 26.5 settling point, so it still rises, only slower).
        assertThat(temp("R33") - before).isCloseTo(0.06 * 6.8 - 0.12 * 0.4 * 8.0, within(1e-9));
    }

    @Test
    void clearingTheFailureRestoresNormalCooling() {
        FailureRecord f = registry.inject(FailureType.COOLING_FAILURE, "Z2", 0, null, "operator");
        for (int i = 0; i < 10; i++) {
            tick();
        }
        double hot = temp("R23");
        assertThat(hot).isGreaterThan(27.5);

        registry.clear(f.id());
        tick();

        assertThat(state.snapshot().zones().get("Z2").health()).isEqualTo(HealthState.OK);
        // With f back at 0.4 the rack loses more heat than it gains at this temperature, so it now cools.
        assertThat(temp("R23")).isLessThan(hot);
    }

    @Test
    void coolingDegradedUsesTheRemainingEfficiencyAsHealthFactor() {
        registry.inject(FailureType.COOLING_DEGRADED, "Z4", 0, 0.5, "operator");
        double before = temp("R41");
        tick();

        var zone = state.snapshot().zones().get("Z4");
        assertThat(zone.health()).isEqualTo(HealthState.DEGRADED);
        assertThat(zone.healthFactorOverride()).isEqualTo(0.5);
        // f = 0.40 * 0.5 = 0.20
        assertThat(temp("R41") - before).isCloseTo(0.06 * 6.8 - 0.12 * 0.20 * 8.0, within(1e-9));
    }

    @Test
    void faultWinsOverDegradedAndTheWorstDegradationWins() {
        registry.inject(FailureType.COOLING_DEGRADED, "Z1", 0, 0.5, "operator");
        registry.inject(FailureType.COOLING_DEGRADED, "Z1", 0, 0.3, "operator");
        registry.inject(FailureType.COOLING_DEGRADED, "Z3", 0, 0.5, "operator");
        registry.inject(FailureType.COOLING_FAILURE, "Z3", 0, null, "operator");
        tick();
        assertThat(state.snapshot().zones().get("Z1").healthFactorOverride()).isEqualTo(0.3);
        assertThat(state.snapshot().zones().get("Z3").health()).isEqualTo(HealthState.FAULT);
        assertThat(state.snapshot().zones().get("Z3").healthFactorOverride()).isNull();
    }

    @Test
    void heatSpikeAddsKilowattsToThePowerOfThatRackOnly() {
        registry.inject(FailureType.HEAT_SPIKE, "R23", 0, 4.0, "operator");
        registry.inject(FailureType.HEAT_SPIKE, "R23", 0, 1.0, "operator");
        tick();
        Snapshot s = state.snapshot();
        assertThat(s.racks().get("R23").heatSpikeKw()).isEqualTo(5.0);
        assertThat(s.racks().get("R23").powerKw()).isCloseTo(6.8 + 5.0, within(1e-12));
        assertThat(s.racks().get("R24").heatSpikeKw()).isZero();
        assertThat(s.racks().get("R24").powerKw()).isCloseTo(6.8, within(1e-12));
        assertThat(temp("R23")).isGreaterThan(temp("R24"));
    }

    @Test
    void heatSpikeStopsAffectingTheSimulationAfterItExpires() {
        registry.inject(FailureType.HEAT_SPIKE, "R23", 4, 6.0, "operator");
        tick();   // t = 2 s, active
        assertThat(state.snapshot().racks().get("R23").heatSpikeKw()).isEqualTo(6.0);
        tick();   // t = 4 s, ends_at reached: expired before this tick's physics
        assertThat(state.snapshot().racks().get("R23").heatSpikeKw()).isZero();
        assertThat(state.snapshot().racks().get("R23").powerKw()).isCloseTo(6.8, within(1e-12));
        assertThat(repo.findAll().get(0).clearedAt()).isEqualTo(T0.plusSeconds(4));
        assertThat(registry.active()).isEmpty();
    }

    @Test
    void sensorDropoutOnlyHidesTheRackFromPublishingWhilePhysicsContinues() {
        // Reference run without any failure.
        double reference;
        {
            SimState refState = new SimState(TestTopology.grid(), new PhysicsModel(PLAN_NO_NOISE, () -> 0.0), INITIAL,
                    clock);
            SimulationEngine ref = new SimulationEngine(TestTopology.grid(),
                    new PhysicsModel(PLAN_NO_NOISE, () -> 0.0), refState, clock);
            for (int i = 0; i < 5; i++) {
                ref.tick();
            }
            reference = refState.snapshot().racks().get("R23").tempC();
        }
        registry.inject(FailureType.SENSOR_DROPOUT, "R23", 0, null, "operator");
        for (int i = 0; i < 5; i++) {
            tick();
        }
        assertThat(temp("R23")).isCloseTo(reference, within(1e-12));
        assertThat(published).hasSize(5).allSatisfy(p -> assertThat(p.dropped()).containsExactly("R23"));
    }

    @Test
    void resetClearsFailuresRestoresInitialStateAndLeavesTheEngineRunning() {
        registry.inject(FailureType.COOLING_FAILURE, "Z1", 0, null, "operator");
        registry.inject(FailureType.HEAT_SPIKE, "R44", 0, 3.0, "operator");
        for (int i = 0; i < 6; i++) {
            tick();
        }
        engine.pause();

        clock.advance(Duration.ofSeconds(30));
        engine.reset();

        Snapshot s = state.snapshot();
        assertThat(s.tick()).isZero();
        assertThat(s.timestamp()).isEqualTo(clock.instant());
        assertThat(s.racks().values()).allSatisfy(r -> {
            assertThat(r.tempC()).isEqualTo(26.0);
            assertThat(r.heatSpikeKw()).isZero();
        });
        assertThat(s.zones().values()).allSatisfy(z -> assertThat(z.health()).isEqualTo(HealthState.OK));
        assertThat(state.isPaused()).isFalse();
        assertThat(registry.active()).isEmpty();
        assertThat(repo.findAll()).hasSize(2).allSatisfy(f -> assertThat(f.clearedAt()).isEqualTo(clock.instant()));

        tick();
        assertThat(state.snapshot().tick()).isEqualTo(1);
        assertThat(state.snapshot().zones().get("Z1").health()).isEqualTo(HealthState.OK);
    }

    @Test
    void anEngineRestartedOverTheSameDatabaseStillAppliesTheFailure() {
        registry.inject(FailureType.COOLING_FAILURE, "Z2", 0, null, "operator");
        tick();

        // New registry + new engine over the same H2 data, as after a simulator restart.
        RackGrid grid = TestTopology.grid();
        PhysicsModel model = new PhysicsModel(PLAN_NO_NOISE, () -> 0.0);
        SimulatorProperties props = new SimulatorProperties(2000, false, null, TestTopology.GRID, INITIAL,
                new SimulatorProperties.Physics(18.0, 0.06, 0.12, 0.05, 0.0, 0.10,
                        new SimulatorProperties.HealthFactors(1.0, 0.25, 0.0)),
                new SimulatorProperties.Publishing(false, "SIMULATOR-SERVICE", 1, 10000),
                new SimulatorProperties.Failures(86400, 50.0), new SimulatorProperties.Bootstrap(1, 1, 1));
        FailureRegistry restored = new FailureRegistry(repo, grid, props, clock);
        SimState freshState = new SimState(grid, model, INITIAL, clock);
        SimulationEngine fresh = new SimulationEngine(grid, model, freshState, clock, restored, TickPublisher.NONE);
        fresh.tick();
        assertThat(freshState.snapshot().zones().get("Z2").health()).isEqualTo(HealthState.FAULT);
    }
}
