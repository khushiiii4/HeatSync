package com.thermogrid.simulator;

import static com.thermogrid.simulator.TestTopology.INITIAL;
import static com.thermogrid.simulator.TestTopology.PLAN_NO_NOISE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.WorkloadAssignedData;
import com.thermogrid.contracts.WorkloadRemovedData;
import com.thermogrid.simulator.events.SimulatorEventDispatcher;
import com.thermogrid.simulator.physics.PhysicsModel;
import com.thermogrid.simulator.sim.SimState;
import com.thermogrid.simulator.sim.SimulationEngine;
import com.thermogrid.simulator.sim.TickPublisher;
import com.thermogrid.simulator.failure.FailureSource;
import com.thermogrid.simulator.workload.LiveEventHandler;
import com.thermogrid.simulator.workload.WorkloadClient;
import com.thermogrid.simulator.workload.WorkloadIntegrationConfig;
import com.thermogrid.simulator.workload.WorkloadLoads;
import com.thermogrid.simulator.workload.WorkloadSyncProperties;
import com.thermogrid.simulator.workload.WorkloadSynchronizer;
import com.thermogrid.simulator.workload.WorkloadView;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

/** Workload-event-driven heat sources: registry rules, engine integration, listener and reconciliation. */
class WorkloadDrivenLoadTest {

    private TestSupport.MutableClock clock;
    private WorkloadLoads loads;
    private SimState state;
    private SimulationEngine engine;

    @BeforeEach
    void setUp() {
        clock = new TestSupport.MutableClock(Instant.parse("2026-11-18T07:10:06Z"));
        loads = new WorkloadLoads(clock, 300_000);
        var grid = TestTopology.grid();
        PhysicsModel model = new PhysicsModel(PLAN_NO_NOISE, () -> 0.0);
        state = new SimState(grid, model, INITIAL, clock);
        engine = new SimulationEngine(grid, model, state, clock, FailureSource.NONE, TickPublisher.NONE, loads);
    }

    private static WorkloadView view(long id, String rack, int cpu, int version) {
        return new WorkloadView(id, cpu, "LOW", "RUNNING", rack, version);
    }

    private double cpu(String rack) {
        return state.snapshot().racks().get(rack).cpuUnits();
    }

    // ---- registry rules ----

    @Test
    void noLoadDataMeansStaticLoadIsKept() {
        assertThat(loads.rackLoads()).isNull();
        engine.tick();
        assertThat(cpu("R11")).isEqualTo(60.0);
    }

    @Test
    void eventsBeforeBootstrapDoNotDriveThePhysics() {
        loads.applyAssigned(1, "R11", 30, 1);
        assertThat(loads.rackLoads()).isNull();
        engine.tick();
        assertThat(cpu("R11")).isEqualTo(60.0);
    }

    @Test
    void bootstrapReplacesStaticLoadWithWorkloadSums() {
        loads.reconcile(List.of(view(1, "R23", 30, 2), view(2, "R23", 20, 0), view(3, "R41", 10, 1)), clock.instant());
        engine.tick();
        assertThat(cpu("R23")).isEqualTo(50.0);
        assertThat(cpu("R41")).isEqualTo(10.0);
        assertThat(cpu("R11")).isZero();           // a rack with no workloads is idle, not at the old static 60
    }

    @Test
    void assignedEventMovesHeatFromOneRackToAnother() {
        loads.reconcile(List.of(view(2, "R23", 30, 2)), clock.instant());
        engine.tick();
        assertThat(cpu("R23")).isEqualTo(30.0);
        assertThat(loads.applyAssigned(2, "R41", 30, 3)).isTrue();
        engine.tick();
        assertThat(cpu("R23")).isZero();
        assertThat(cpu("R41")).isEqualTo(30.0);
    }

    @Test
    void duplicateAndOutOfOrderAssignedEventsAreIgnored() {
        loads.reconcile(List.of(), clock.instant());
        assertThat(loads.applyAssigned(5, "R11", 20, 1)).isTrue();
        assertThat(loads.applyAssigned(5, "R11", 20, 1)).isFalse();          // duplicate
        assertThat(loads.applyAssigned(5, "R12", 20, 3)).isTrue();
        assertThat(loads.applyAssigned(5, "R13", 20, 2)).isFalse();          // late, older than v3
        assertThat(loads.rackLoads()).containsEntry("R12", 20.0).doesNotContainKey("R11").doesNotContainKey("R13");
    }

    @Test
    void removedEventDropsTheHeatAndALateAssignedCannotResurrectIt() {
        loads.reconcile(List.of(view(7, "R11", 25, 1)), clock.instant());
        assertThat(loads.applyRemoved(7)).isTrue();
        assertThat(loads.rackLoads()).doesNotContainKey("R11");
        assertThat(loads.applyAssigned(7, "R11", 25, 2)).isFalse();
        assertThat(loads.applyRemoved(7)).isFalse();                          // duplicate removal is harmless
        assertThat(loads.applyRemoved(404)).isFalse();                        // unknown id is harmless
    }

    @Test
    void invalidAssignedEventsAreIgnored() {
        loads.reconcile(List.of(), clock.instant());
        assertThat(loads.applyAssigned(1, null, 10, 1)).isFalse();
        assertThat(loads.applyAssigned(1, " ", 10, 1)).isFalse();
        assertThat(loads.applyAssigned(1, "R11", -5, 1)).isFalse();
        assertThat(loads.workloadCount()).isZero();
    }

    @Test
    void rackLoadIsCappedAtCapacityAndMaintenanceRacksStayCold() {
        var grid = TestTopology.grid(java.util.Set.of("R12"));
        PhysicsModel model = new PhysicsModel(PLAN_NO_NOISE, () -> 0.0);
        SimState st = new SimState(grid, model, INITIAL, clock);
        SimulationEngine eng = new SimulationEngine(grid, model, st, clock, FailureSource.NONE, TickPublisher.NONE,
                loads);
        loads.reconcile(List.of(view(1, "R11", 90, 0), view(2, "R11", 60, 0), view(3, "R12", 40, 0)), clock.instant());
        eng.tick();
        assertThat(st.snapshot().racks().get("R11").cpuUnits()).isEqualTo(100.0);
        assertThat(st.snapshot().racks().get("R12").cpuUnits()).isZero();
    }

    @Test
    void addingWorkloadHeatRaisesTheRackTemperature() {
        loads.reconcile(List.of(), clock.instant());
        for (int i = 0; i < 150; i++) {
            engine.tick();
        }
        double cold = state.snapshot().racks().get("R23").tempC();
        loads.applyAssigned(1, "R23", 80, 1);
        for (int i = 0; i < 150; i++) {
            engine.tick();
        }
        double hot = state.snapshot().racks().get("R23").tempC();
        assertThat(hot).isGreaterThan(cold + 3.0);
        loads.applyRemoved(1);
        for (int i = 0; i < 150; i++) {
            engine.tick();
        }
        assertThat(state.snapshot().racks().get("R23").tempC()).isLessThan(hot - 3.0);
    }

    @Test
    void publishedLoadPercentFollowsWorkloads() {
        loads.reconcile(List.of(view(1, "R23", 72, 0)), clock.instant());
        engine.tick();
        var rack = state.snapshot().racks().get("R23");
        assertThat(rack.cpuUnits() / 100.0 * 100.0).isCloseTo(72.0, within(1e-9));
    }

    // ---- reconciliation ----

    @Test
    void reconcileRepairsAMissedEventAndDropsPhantoms() {
        loads.reconcile(List.of(view(1, "R11", 20, 0), view(2, "R12", 20, 0)), clock.instant());
        clock.advance(Duration.ofSeconds(60));
        // Truth: workload 1 moved to R13, workload 2 is gone, workload 3 is new. The simulator saw none of it.
        loads.reconcile(List.of(view(1, "R13", 20, 1), view(3, "R14", 15, 0)), clock.instant());
        assertThat(loads.rackLoads()).containsOnlyKeys("R13", "R14");
    }

    @Test
    void reconcileKeepsEventsAppliedAfterTheFetchStarted() {
        loads.reconcile(List.of(), clock.instant());
        Instant fetchStarted = clock.instant();
        clock.advance(Duration.ofMillis(100));
        loads.applyAssigned(9, "R21", 30, 1);                   // arrives while the list is in flight
        loads.applyRemoved(10);                                  // removal arrives while in flight
        loads.reconcile(List.of(view(10, "R22", 20, 1)), fetchStarted);   // the list still contains 10
        assertThat(loads.rackLoads()).containsEntry("R21", 30.0).doesNotContainKey("R22");
    }

    @Test
    void reconcileDoesNotDowngradeANewerLocalVersion() {
        loads.reconcile(List.of(view(1, "R11", 20, 1)), clock.instant());
        loads.applyAssigned(1, "R12", 20, 2);
        loads.reconcile(List.of(view(1, "R11", 20, 1)), clock.instant().minusSeconds(1));
        assertThat(loads.rackLoads()).containsKey("R12").doesNotContainKey("R11");
    }

    @Test
    void synchronizerBootstrapsRetriesAndKeepsStateOnFailure() {
        int[] calls = {0};
        boolean[] fail = {true};
        WorkloadClient client = status -> {
            calls[0]++;
            assertThat(status).isEqualTo("RUNNING");
            if (fail[0]) {
                throw new IllegalStateException("workload-service down");
            }
            return List.of(view(1, "R11", 40, 0));
        };
        var props = new WorkloadSyncProperties(false, 60_000, 5_000, 300_000, 1000, "");
        var sync = new WorkloadSynchronizer(client, loads, props, clock);

        assertThat(sync.sync()).isFalse();
        assertThat(loads.isLoaded()).isFalse();
        assertThat(loads.rackLoads()).isNull();                  // still static, not emptied

        fail[0] = false;
        assertThat(sync.sync()).isTrue();
        assertThat(loads.rackLoads()).containsEntry("R11", 40.0);

        fail[0] = true;
        assertThat(sync.sync()).isFalse();
        assertThat(loads.rackLoads()).containsEntry("R11", 40.0);   // last known state survives an outage
    }

    // ---- RabbitMQ listener path ----

    private Message message(String routingKey, Object data) throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var env = new EventEnvelope<>(UUID.randomUUID(), routingKey, Instant.now(), "WORKLOAD-SERVICE", 1, data);
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey(routingKey);
        return MessageBuilder.withBody(mapper.writeValueAsBytes(env)).andProperties(props).build();
    }

    @Test
    void listenerAppliesAssignedAndRemovedMessages() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var dispatcher = new SimulatorEventDispatcher(mapper, new LiveEventHandler(loads));
        var listener = newListener(dispatcher);
        loads.reconcile(List.of(), clock.instant());

        listener.onMessage(message("workload.assigned",
                new WorkloadAssignedData(2, 30, "LOW", "R23", "R41", "THERMAL_WARNING", UUID.randomUUID().toString(), 3)));
        assertThat(loads.rackLoads()).containsEntry("R41", 30.0);

        listener.onMessage(message("workload.removed", new WorkloadRemovedData(2, "R41", 30)));
        assertThat(loads.rackLoads()).doesNotContainKey("R41");
    }

    @Test
    void listenerRejectsMalformedMessagesSoTheyDeadLetter() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var listener = newListener(new SimulatorEventDispatcher(mapper, new LiveEventHandler(loads)));
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("workload.assigned");
        Message bad = MessageBuilder.withBody("{not json".getBytes(StandardCharsets.UTF_8)).andProperties(props).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> listener.onMessage(bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static WorkloadIntegrationConfig.WorkloadMessageListener newListener(SimulatorEventDispatcher d) {
        return new WorkloadIntegrationConfig.WorkloadMessageListener(d);
    }
}
