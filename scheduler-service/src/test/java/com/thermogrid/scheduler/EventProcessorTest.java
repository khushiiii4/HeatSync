package com.thermogrid.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.contracts.CoolingUnitStateData;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.ThermalAlertData;
import com.thermogrid.contracts.WorkloadSubmittedData;
import com.thermogrid.scheduler.service.CycleTrigger;
import com.thermogrid.scheduler.service.EventProcessor;
import com.thermogrid.scheduler.service.EventProcessor.MalformedEventException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Event idempotency, 30-second freshness and the reaction to each consumed event type. */
class EventProcessorTest extends SchedulerTestBase {

    @Autowired EventProcessor processor;
    @Autowired ObjectMapper mapper;
    @MockitoBean CycleTrigger trigger;

    private byte[] envelope(String type, UUID id, Instant at, Object data) throws Exception {
        return mapper.writeValueAsBytes(new EventEnvelope<>(id, type, at, "TEST", 1, data));
    }

    private Instant ago(int seconds) {
        return clock.instant().minusSeconds(seconds);
    }

    // ---- workload.submitted ----

    @Test
    void freshSubmissionTriggersPlacementAndIsRecordedAsProcessed() throws Exception {
        UUID id = UUID.randomUUID();
        processor.handle("workload.submitted", envelope("workload.submitted", id, ago(1),
                new WorkloadSubmittedData(7, "batch-etl-7", 30, "LOW")));
        verify(trigger, times(1)).requestPlacement();
        assertThat(processed.existsById(id.toString())).isTrue();
    }

    @Test
    void duplicateSubmissionIsIgnored() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] body = envelope("workload.submitted", id, ago(1), new WorkloadSubmittedData(7, "x", 30, "LOW"));
        processor.handle("workload.submitted", body);
        processor.handle("workload.submitted", body);
        processor.handle("workload.submitted", body);
        verify(trigger, times(1)).requestPlacement();
        assertThat(processed.count()).isEqualTo(1);
    }

    @Test
    void staleSubmissionIsRecordedButNotActedOnImmediately() throws Exception {
        UUID id = UUID.randomUUID();
        processor.handle("workload.submitted", envelope("workload.submitted", id, ago(31),
                new WorkloadSubmittedData(7, "x", 30, "LOW")));
        verify(trigger, never()).requestPlacement();                    // the 3 s pending loop will find it
        assertThat(processed.existsById(id.toString())).isTrue();
    }

    @Test
    void eventExactlyThirtySecondsOldIsStillFresh() throws Exception {
        processor.handle("workload.submitted", envelope("workload.submitted", UUID.randomUUID(), ago(30),
                new WorkloadSubmittedData(7, "x", 30, "LOW")));
        verify(trigger, times(1)).requestPlacement();
    }

    // ---- thermal.alert.* ----

    private byte[] alert(String type, String level, Instant at) throws Exception {
        return envelope(type, UUID.randomUUID(), at,
                new ThermalAlertData(412, "R23", "Z2", level, "WATCH", "PREDICTED", 30.4, 35.2));
    }

    @Test
    void warningAndCriticalAlertsTriggerMigrationAndMarkTheRack() throws Exception {
        processor.handle("thermal.alert.raised", alert("thermal.alert.raised", "WARNING", ago(2)));
        verify(trigger, times(1)).requestReconcile();
        assertThat(state.alertRacks()).containsKey("R23");
        processor.handle("thermal.alert.raised", alert("thermal.alert.raised", "CRITICAL", ago(1)));
        verify(trigger, times(2)).requestReconcile();
    }

    @Test
    void loweredAndClearedAlertsNeedNoImmediateAction() throws Exception {
        processor.handle("thermal.alert.lowered", alert("thermal.alert.lowered", "WATCH", ago(1)));
        processor.handle("thermal.alert.cleared", alert("thermal.alert.cleared", "NORMAL", ago(1)));
        verify(trigger, never()).requestReconcile();
        assertThat(state.alertRacks()).isEmpty();
    }

    @Test
    void staleAlertIsNotTrusted() throws Exception {
        processor.handle("thermal.alert.raised", alert("thermal.alert.raised", "CRITICAL", ago(45)));
        verify(trigger, never()).requestReconcile();
        assertThat(state.alertRacks()).isEmpty();
    }

    @Test
    void duplicateAlertDeliveryIsHarmless() throws Exception {
        byte[] body = alert("thermal.alert.raised", "WARNING", ago(1));
        processor.handle("thermal.alert.raised", body);
        processor.handle("thermal.alert.raised", body);
        assertThat(state.alertRacks()).hasSize(1);                       // absolute state: a repeat changes nothing
    }

    // ---- cooling.unit.state ----

    private byte[] cooling(String health, Instant at, long seq) throws Exception {
        return envelope("cooling.unit.state", UUID.randomUUID(), at,
                new CoolingUnitStateData("Z3", "AUTO", 80, health, 26.0, "test", seq));
    }

    @Test
    void faultMarksTheZoneAndTriggersDraining() throws Exception {
        processor.handle("cooling.unit.state", cooling("FAULT", ago(1), 5));
        assertThat(cooling.findById("Z3")).isPresent();
        assertThat(cooling.findById("Z3").get().getHealth()).isEqualTo("FAULT");
        verify(trigger, times(1)).requestReconcile();
    }

    @Test
    void recoveryUpdatesTheStateAndOutOfOrderOldMessagesAreIgnored() throws Exception {
        processor.handle("cooling.unit.state", cooling("FAULT", ago(10), 5));
        processor.handle("cooling.unit.state", cooling("OK", ago(20), 4));      // older than the stored state
        assertThat(cooling.findById("Z3").get().getHealth()).isEqualTo("FAULT");
        processor.handle("cooling.unit.state", cooling("OK", ago(2), 6));       // newer: zone recovered
        assertThat(cooling.findById("Z3").get().getHealth()).isEqualTo("OK");
        verify(trigger, times(1)).requestReconcile();                              // only the FAULT triggered
    }

    @Test
    void staleCoolingEventIsIgnoredCompletely() throws Exception {
        processor.handle("cooling.unit.state", cooling("FAULT", ago(31), 5));
        assertThat(cooling.findById("Z3")).isEmpty();
        verify(trigger, never()).requestReconcile();
    }

    @Test
    void healthyHeartbeatsKeepAnOkZoneOk() throws Exception {
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofSeconds(10));
            processor.handle("cooling.unit.state", cooling("OK", clock.instant(), i));
        }
        assertThat(cooling.findById("Z3").get().getHealth()).isEqualTo("OK");
        verify(trigger, never()).requestReconcile();
    }

    // ---- malformed ----

    @Test
    void malformedMessagesAreRejectedForDeadLettering() {
        assertThatThrownBy(() -> processor.handle("workload.submitted", "{not json".getBytes()))
                .isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> processor.handle("workload.submitted", "{}".getBytes()))
                .isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> processor.handle("workload.assigned", "{}".getBytes()))
                .isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> processor.handle("thermal.alert.raised",
                envelope("workload.submitted", UUID.randomUUID(), ago(1),
                        new WorkloadSubmittedData(1, "x", 1, "LOW"))))
                .isInstanceOf(MalformedEventException.class);
        verify(trigger, never()).requestPlacement();
    }
}
