package com.thermogrid.simulator;

import static com.thermogrid.simulator.TestTopology.INITIAL;
import static com.thermogrid.simulator.TestTopology.PLAN_NO_NOISE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thermogrid.contracts.CoolingUnitStateData;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.simulator.events.SimulatorEventDispatcher;
import com.thermogrid.simulator.failure.FailureRecord;
import com.thermogrid.simulator.failure.FailureSource;
import com.thermogrid.simulator.failure.FailureType;
import com.thermogrid.simulator.physics.PhysicsModel;
import com.thermogrid.simulator.sim.SimState;
import com.thermogrid.simulator.sim.SimulationEngine;
import com.thermogrid.simulator.sim.TickPublisher;
import com.thermogrid.simulator.workload.CoolingInputs;
import com.thermogrid.simulator.workload.LiveEventHandler;
import com.thermogrid.simulator.workload.WorkloadIntegrationConfig;
import com.thermogrid.simulator.workload.WorkloadLoads;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

/** cooling.unit.state drives the fan output of a zone, and nothing else. */
class CoolingIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-11-18T07:10:06Z");

    private TestSupport.MutableClock clock;
    private CoolingInputs cooling;
    private SimState state;
    private SimulationEngine engine;
    private volatile List<FailureRecord> failures = List.of();

    @BeforeEach
    void setUp() {
        clock = new TestSupport.MutableClock(T0);
        cooling = new CoolingInputs();
        var grid = TestTopology.grid();
        PhysicsModel model = new PhysicsModel(PLAN_NO_NOISE, () -> 0.0);
        state = new SimState(grid, model, INITIAL, clock);
        FailureSource src = new FailureSource() {
            @Override public List<FailureRecord> activeAt(Instant now) { return failures; }
            @Override public int clearAll(Instant now) { return 0; }
        };
        engine = new SimulationEngine(grid, model, state, clock, src, TickPublisher.NONE,
                com.thermogrid.simulator.sim.LoadSource.STATIC, cooling);
    }

    private double output(String zone) {
        return state.snapshot().zones().get(zone).outputPct();
    }

    private double temp(String rack) {
        return state.snapshot().racks().get(rack).tempC();
    }

    @Test
    void untilTheControllerSpeaksZonesKeepTheirInitialOutput() {
        engine.tick();
        assertThat(output("Z2")).isEqualTo(40.0);
    }

    @Test
    void commandedOutputAppliesToTheCorrectZoneOnTheNextTick() {
        assertThat(cooling.apply("Z2", 85, "AUTO", "OK", 1, T0)).isTrue();
        engine.tick();
        assertThat(output("Z2")).isEqualTo(85.0);
        assertThat(output("Z1")).isEqualTo(40.0);
        assertThat(output("Z3")).isEqualTo(40.0);
        cooling.apply("Z2", 55, "AUTO", "OK", 2, T0.plusSeconds(2));
        engine.tick();
        assertThat(output("Z2")).isEqualTo(55.0);
    }

    @Test
    void moreCoolingLowersTheTemperatureTheSamePhysicsEquationGivesLessHeat() {
        double[] t = new double[2];
        for (int run = 0; run < 2; run++) {
            setUp();
            cooling.apply("Z2", run == 0 ? 40 : 100, "AUTO", "OK", 1, T0);
            for (int i = 0; i < 60; i++) {
                engine.tick();
            }
            t[run] = temp("R22");
        }
        assertThat(t[1]).isLessThan(t[0] - 0.5);
    }

    @Test
    void duplicateAndOutOfOrderMessagesAreIgnored() {
        assertThat(cooling.apply("Z2", 70, "AUTO", "OK", 5, T0)).isTrue();
        assertThat(cooling.apply("Z2", 70, "AUTO", "OK", 5, T0)).isFalse();                // duplicate
        assertThat(cooling.apply("Z2", 30, "AUTO", "OK", 4, T0.minusSeconds(2))).isFalse(); // older seq and time
        assertThat(cooling.apply("Z2", 30, "AUTO", "OK", 4, T0.plusSeconds(2))).isFalse();  // lower seq, newer, within 30 s
        assertThat(cooling.apply("Z2", 30, "AUTO", "OK", 4, T0.plusSeconds(30))).isFalse(); // the window is exclusive
        assertThat(cooling.outputPct("Z2").getAsDouble()).isEqualTo(70.0);
        assertThat(cooling.apply("Z2", 80, "AUTO", "OK", 6, T0.plusSeconds(2))).isTrue();
        assertThat(cooling.outputPct("Z2").getAsDouble()).isEqualTo(80.0);
    }

    @Test
    void aRestartedControllerWithAResetSequenceIsAcceptedBecauseItIsNewer() {
        cooling.apply("Z2", 70, "AUTO", "OK", 500, T0);
        assertThat(cooling.apply("Z2", 55, "AUTO", "OK", 1, T0.plusSeconds(60))).isTrue();
        assertThat(cooling.outputPct("Z2").getAsDouble()).isEqualTo(55.0);
    }

    @Test
    void outputIsClampedAndInvalidMessagesAreDropped() {
        cooling.apply("Z1", 250, "AUTO", "OK", 1, T0);
        assertThat(cooling.outputPct("Z1").getAsDouble()).isEqualTo(100.0);
        cooling.apply("Z1", -10, "AUTO", "OK", 2, T0.plusSeconds(1));
        assertThat(cooling.outputPct("Z1").getAsDouble()).isEqualTo(0.0);
        assertThat(cooling.apply("", 50, "AUTO", "OK", 1, T0)).isFalse();
        assertThat(cooling.apply("Z1", Double.NaN, "AUTO", "OK", 9, T0.plusSeconds(5))).isFalse();
    }

    @Test
    void theLastOutputIsHeldWhileTheControllerIsSilent() {
        cooling.apply("Z3", 90, "AUTO", "OK", 1, T0);
        for (int i = 0; i < 100; i++) {
            clock.advance(java.time.Duration.ofSeconds(2));
            engine.tick();
        }
        assertThat(output("Z3")).isEqualTo(90.0);
    }

    @Test
    void controllerHealthIsNotAppliedToThePhysicsSoADegradationIsNeverCountedTwice() {
        // Controller says FAULT for Z3, but no failure is injected: the plant is healthy, so the zone is still cooled.
        cooling.apply("Z3", 100, "AUTO", "FAULT", 1, T0);
        engine.tick();
        assertThat(state.snapshot().zones().get("Z3").health().name()).isEqualTo("OK");
        // The injected failure alone defines the plant health, whatever the controller reports.
        failures = List.of(new FailureRecord(1, FailureType.COOLING_FAILURE, "Z3", null, T0, null, null, "test"));
        cooling.apply("Z3", 100, "AUTO", "OK", 2, T0.plusSeconds(2));
        engine.tick();
        assertThat(state.snapshot().zones().get("Z3").health().name()).isEqualTo("FAULT");
        // Clearing the injection restores OK at once: the controller's FAULT can not latch the plant.
        failures = List.of();
        cooling.apply("Z3", 100, "AUTO", "FAULT", 3, T0.plusSeconds(4));
        engine.tick();
        assertThat(state.snapshot().zones().get("Z3").health().name()).isEqualTo("OK");
    }

    @Test
    void injectedDegradationKeepsItsOwnEfficiencyWhileTheOutputIsCommanded() {
        failures = List.of(new FailureRecord(1, FailureType.COOLING_DEGRADED, "Z2", 0.25, T0, null, null, "test"));
        cooling.apply("Z2", 100, "AUTO", "OK", 1, T0);
        engine.tick();
        var z = state.snapshot().zones().get("Z2");
        assertThat(z.outputPct()).isEqualTo(100.0);
        assertThat(z.health().name()).isEqualTo("DEGRADED");
        assertThat(z.healthFactorOverride()).isEqualTo(0.25);
    }

    // ---- listener path ----

    private Message message(String type, Object data, Instant at) throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var env = new EventEnvelope<>(UUID.randomUUID(), type, at, "COOLING-CONTROLLER-SERVICE", 1, data);
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey(type);
        return MessageBuilder.withBody(mapper.writeValueAsBytes(env)).andProperties(props).build();
    }

    @Test
    void theListenerAppliesCoolingStateMessagesAndRejectsMalformedOnes() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var dispatcher = new SimulatorEventDispatcher(mapper, new LiveEventHandler(new WorkloadLoads(clock, 1000), cooling));
        var listener = new WorkloadIntegrationConfig.CoolingMessageListener(dispatcher);

        listener.onMessage(message("cooling.unit.state",
                new CoolingUnitStateData("Z2", "AUTO", 78, "OK", 26.0, "hotspot forecast above target", 1042), T0));
        assertThat(cooling.outputPct("Z2").getAsDouble()).isEqualTo(78.0);
        listener.onMessage(message("cooling.unit.state",
                new CoolingUnitStateData("Z2", "AUTO", 78, "OK", 26.0, "heartbeat", 1042), T0));       // duplicate
        assertThat(cooling.get("Z2").get().seq()).isEqualTo(1042);

        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("cooling.unit.state");
        Message bad = MessageBuilder.withBody("{oops".getBytes()).andProperties(props).build();
        // Phase 7: a state queue drops a bad message (logged) instead of retrying or dead-lettering it.
        listener.onMessage(bad);
        assertThat(cooling.get("Z2").get().seq()).isEqualTo(1042);
    }
}
