package com.thermogrid.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thermogrid.contracts.CoolingUnitStateData;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.contracts.WorkloadAssignedData;
import com.thermogrid.contracts.WorkloadRemovedData;
import com.thermogrid.simulator.events.SimulatorEventDispatcher;
import com.thermogrid.simulator.events.SimulatorEventHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract mapping only: the simulator can already decode the three events it will consume later. */
class EventDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final List<Object> received = new ArrayList<>();
    private final SimulatorEventDispatcher dispatcher = new SimulatorEventDispatcher(MAPPER,
            new SimulatorEventHandler() {
                @Override
                public void onWorkloadAssigned(EventEnvelope<WorkloadAssignedData> e) {
                    received.add(e);
                }

                @Override
                public void onWorkloadRemoved(EventEnvelope<WorkloadRemovedData> e) {
                    received.add(e);
                }

                @Override
                public void onCoolingUnitState(EventEnvelope<CoolingUnitStateData> e) {
                    received.add(e);
                }
            });

    private static byte[] json(String eventType, String data) {
        return ("{\"eventId\":\"0b8e5c1e-6f3a-4a9e-8d57-1c2b3a4d5e6f\",\"eventType\":\"" + eventType
                + "\",\"occurredAt\":\"2026-11-18T07:10:06.310Z\",\"producer\":\"workload-service\",\"version\":1,"
                + "\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void workloadAssignedIsMappedToTheContractRecord() {
        dispatcher.dispatch(RoutingKeys.WORKLOAD_ASSIGNED, json("workload.assigned",
                "{\"workloadId\":2,\"cpuDemand\":30,\"priority\":\"LOW\",\"fromRackId\":\"R23\",\"toRackId\":\"R41\","
                        + "\"reason\":\"THERMAL_WARNING\",\"migrationId\":\"5b0c8e2a-41d7-4c1e-9b7a-2f6d0e8a1c33\","
                        + "\"version\":3}"));

        EventEnvelope<WorkloadAssignedData> env = (EventEnvelope<WorkloadAssignedData>) received.get(0);
        assertThat(env.occurredAt()).isEqualTo(Instant.parse("2026-11-18T07:10:06.310Z"));
        assertThat(env.data()).isEqualTo(new WorkloadAssignedData(2, 30, "LOW", "R23", "R41", "THERMAL_WARNING",
                "5b0c8e2a-41d7-4c1e-9b7a-2f6d0e8a1c33", 3));
    }

    @Test
    @SuppressWarnings("unchecked")
    void workloadRemovedAndCoolingUnitStateAreMapped() {
        dispatcher.dispatch(RoutingKeys.WORKLOAD_REMOVED, json("workload.removed",
                "{\"workloadId\":18,\"rackId\":\"R41\",\"cpuDemand\":30}"));
        dispatcher.dispatch(RoutingKeys.COOLING_UNIT_STATE, json("cooling.unit.state",
                "{\"zoneId\":\"Z2\",\"mode\":\"AUTO\",\"outputPct\":78,\"health\":\"OK\",\"targetTempC\":26.0,"
                        + "\"reason\":\"hotspot forecast above target\",\"seq\":1042}"));

        assertThat(((EventEnvelope<WorkloadRemovedData>) received.get(0)).data())
                .isEqualTo(new WorkloadRemovedData(18, "R41", 30));
        assertThat(((EventEnvelope<CoolingUnitStateData>) received.get(1)).data()).isEqualTo(
                new CoolingUnitStateData("Z2", "AUTO", 78, "OK", 26.0, "hotspot forecast above target", 1042));
    }

    @Test
    void firstPlacementWithNullFromRackIsAccepted() {
        dispatcher.dispatch(RoutingKeys.WORKLOAD_ASSIGNED, json("workload.assigned",
                "{\"workloadId\":18,\"cpuDemand\":30,\"priority\":\"LOW\",\"fromRackId\":null,\"toRackId\":\"R41\","
                        + "\"reason\":\"INITIAL_PLACEMENT\",\"migrationId\":\"x\",\"version\":1}"));
        assertThat(received).hasSize(1);
    }

    @Test
    void unknownKeyMalformedBodyAndKeyMismatchAreRejected() {
        assertThatThrownBy(() -> dispatcher.dispatch(RoutingKeys.TELEMETRY_RACK_READING, json("x", "{}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not consume");
        assertThatThrownBy(() -> dispatcher.dispatch(RoutingKeys.WORKLOAD_REMOVED, "not json".getBytes()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Malformed workload.removed");
        assertThatThrownBy(() -> dispatcher.dispatch(RoutingKeys.WORKLOAD_REMOVED, json("workload.assigned",
                "{\"workloadId\":1,\"rackId\":\"R11\",\"cpuDemand\":1}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match eventType");
        assertThat(received).isEmpty();
    }
}
