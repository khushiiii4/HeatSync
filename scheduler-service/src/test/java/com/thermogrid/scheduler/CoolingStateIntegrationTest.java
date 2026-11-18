package com.thermogrid.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.contracts.CoolingUnitStateData;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.scheduler.model.Model.Mode;
import com.thermogrid.scheduler.service.CycleTrigger;
import com.thermogrid.scheduler.service.EventProcessor;
import com.thermogrid.scheduler.service.SchedulerCycles;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** cooling.unit.state from the cooling controller: FAULT drains and excludes a zone, confirmed recovery restores it. */
class CoolingStateIntegrationTest extends SchedulerTestBase {

    @Autowired EventProcessor processor;
    @Autowired SchedulerCycles cycles;
    @Autowired ObjectMapper mapper;
    @MockitoBean CycleTrigger trigger;

    private void cooling(String zone, String health, int output, long seq, Instant at) throws Exception {
        byte[] body = mapper.writeValueAsBytes(new EventEnvelope<>(UUID.randomUUID(), "cooling.unit.state", at,
                "COOLING-CONTROLLER-SERVICE", 1,
                new CoolingUnitStateData(zone, "AUTO", output, health, 26.0, "test", seq)));
        processor.handle("cooling.unit.state", body);
    }

    private Instant ago(int s) {
        return clock.instant().minusSeconds(s);
    }

    @Test
    void confirmedFaultTriggersADrainOfTheZoneAndItIsNeverADestination() throws Exception {
        baselineLoad();
        running.add(wl(1, 20, "HIGH", "R31", 1));
        running.add(wl(2, 20, "LOW", "R32", 1));
        cooling("Z3", "FAULT", 60, 7, ago(1));
        verify(trigger, atLeastOnce()).requestReconcile();
        assertThat(cooling.findById("Z3").orElseThrow().getHealth()).isEqualTo("FAULT");

        cycles.reconcile();
        // LOW first, and nothing lands in Z3 (R31..R34).
        assertThat(assignedIds.get(0)).isEqualTo(2L);                 // the only LOW job leaves first
        assertThat(assignedIds).hasSize(4);                           // draining may move 4 per cycle
        assertThat(assignedIds).doesNotContain(1L);                   // the HIGH job waits behind the MEDIUM ones
        assertThat(assignments).allMatch(a -> a.reason().equals("COOLING_FAILURE") && !a.toRackId().startsWith("R3"));

        // New work is not placed into the failed zone either, even if it is the only zone with free racks.
        assignments.clear();
        for (String r : forecast.keySet()) {
            if (!r.startsWith("R3")) {
                sensor.put(r, "STALE");
            }
        }
        pending.add(wl(50, 10, "LOW", null, 0));
        assertThat(cycles.placePending()).isZero();
        assertThat(assignments).isEmpty();
    }

    @Test
    void confirmedRecoveryRestoresEligibilityForPlacement() throws Exception {
        for (String r : forecast.keySet()) {
            if (!r.startsWith("R3")) {
                sensor.put(r, "STALE");                   // only Z3 could take work
            }
        }
        cooling("Z3", "FAULT", 60, 1, ago(5));
        pending.add(wl(50, 10, "LOW", null, 0));
        assertThat(cycles.placePending()).isZero();

        clock.advance(Duration.ofSeconds(6));
        cooling("Z3", "OK", 40, 2, ago(1));                   // the controller has confirmed 3 OK readings
        assertThat(cooling.findById("Z3").orElseThrow().getHealth()).isEqualTo("OK");
        assertThat(cycles.placePending()).isEqualTo(1);
        assertThat(assignments.get(0).toRackId()).startsWith("R3");
    }

    @Test
    void staleDuplicateAndOutOfOrderCoolingMessagesAreSafe() throws Exception {
        cooling("Z3", "FAULT", 60, 5, ago(10));
        cooling("Z3", "OK", 40, 4, ago(20));                  // older than the stored state: ignored
        assertThat(cooling.findById("Z3").orElseThrow().getHealth()).isEqualTo("FAULT");
        cooling("Z3", "FAULT", 60, 5, ago(10));                // duplicate delivery
        assertThat(cooling.count()).isEqualTo(1);
        cooling("Z3", "OK", 40, 6, ago(45));                   // older than 30 s: not trusted
        assertThat(cooling.findById("Z3").orElseThrow().getHealth()).isEqualTo("FAULT");
        clock.advance(Duration.ofSeconds(1));
        cooling("Z3", "OK", 40, 7, ago(0));
        assertThat(cooling.findById("Z3").orElseThrow().getHealth()).isEqualTo("OK");
    }

    @Test
    void drainingStillRespectsHoldCooldownsAndMoveLimits() throws Exception {
        // HOLD: no thermal migration even for a confirmed FAULT.
        telemetryUp = false;
        for (int i = 0; i < 6; i++) {
            running.add(wl(100 + i, 10, "LOW", "R31", 1));
        }
        cooling("Z3", "FAULT", 60, 1, ago(1));
        cycles.reconcile();
        assertThat(state.mode()).isEqualTo(Mode.HOLD);
        assertThat(assignments).isEmpty();

        // NORMAL: at most 4 moves per cycle while draining.
        telemetryUp = true;
        guarded.telemetryBreaker().reset();
        cycles.reconcile();
        assertThat(assignments).hasSize(4);
        // Moved workloads are in their cooldown: the next cycle does not touch them again.
        clock.advance(Duration.ofSeconds(30));
        cycles.reconcile();
        assertThat(assignedIds.stream().distinct().count()).isEqualTo(assignedIds.size());
        assertThat(assignedIds).hasSize(6);
    }

    @Test
    void healthyZonesAreUntouchedByAnOkHeartbeat() throws Exception {
        baselineLoad();
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofSeconds(5));
            cooling("Z2", "OK", 55, i + 1, ago(0));
        }
        cycles.reconcile();
        assertThat(assignments).isEmpty();
        assertThat(cooling.findById("Z2").orElseThrow().getHealth()).isEqualTo("OK");
    }
}
