package com.thermogrid.simulator.events;

import java.io.IOException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.contracts.CoolingUnitStateData;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.contracts.WorkloadAssignedData;
import com.thermogrid.contracts.WorkloadRemovedData;
import org.springframework.stereotype.Component;

/**
 * Maps a raw message body plus its routing key onto the contract records and hands it to the
 * {@link SimulatorEventHandler}. It is deliberately NOT connected to RabbitMQ yet: no queue is declared and no
 * listener container runs, so the simulator consumes nothing until the producing services exist.
 */
@Component
public class SimulatorEventDispatcher {

    /** Only schema version 1 is understood (plan 8.5). */
    static final int SUPPORTED_VERSION = 1;

    private final ObjectMapper mapper;
    private final SimulatorEventHandler handler;

    public SimulatorEventDispatcher(ObjectMapper mapper, SimulatorEventHandler handler) {
        this.mapper = mapper;
        this.handler = handler;
    }

    /** @throws IllegalArgumentException for an unknown routing key, an unparsable body or a key/eventType mismatch */
    public void dispatch(String routingKey, byte[] body) {
        try {
            switch (routingKey) {
                case RoutingKeys.WORKLOAD_ASSIGNED -> {
                    var e = read(body, routingKey, new TypeReference<EventEnvelope<WorkloadAssignedData>>() { });
                    WorkloadAssignedData d = e.data();
                    if (d.workloadId() <= 0 || d.cpuDemand() < 0 || d.cpuDemand() > 100 || blank(d.toRackId())) {
                        throw new IllegalArgumentException("Invalid workload.assigned payload: " + d);
                    }
                    handler.onWorkloadAssigned(e);
                }
                case RoutingKeys.WORKLOAD_REMOVED -> {
                    var e = read(body, routingKey, new TypeReference<EventEnvelope<WorkloadRemovedData>>() { });
                    if (e.data().workloadId() <= 0) {
                        throw new IllegalArgumentException("Invalid workload.removed payload: " + e.data());
                    }
                    handler.onWorkloadRemoved(e);
                }
                case RoutingKeys.COOLING_UNIT_STATE -> {
                    var e = read(body, routingKey, new TypeReference<EventEnvelope<CoolingUnitStateData>>() { });
                    CoolingUnitStateData d = e.data();
                    if (blank(d.zoneId()) || d.outputPct() < 0 || d.outputPct() > 100 || d.seq() < 0) {
                        throw new IllegalArgumentException("Invalid cooling.unit.state payload: " + d);
                    }
                    handler.onCoolingUnitState(e);
                }
                default -> throw new IllegalArgumentException("Simulator does not consume " + routingKey);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed " + routingKey + " message", e);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private <T> EventEnvelope<T> read(byte[] body, String routingKey, TypeReference<EventEnvelope<T>> type)
            throws IOException {
        EventEnvelope<T> env = mapper.readValue(body, type);
        if (env == null || env.eventId() == null || env.data() == null || env.occurredAt() == null) {
            throw new IllegalArgumentException("Incomplete " + routingKey + " envelope");
        }
        if (env.version() != SUPPORTED_VERSION) {
            throw new IllegalArgumentException("Unsupported " + routingKey + " event version " + env.version());
        }
        if (!routingKey.equals(env.eventType())) {
            throw new IllegalArgumentException("Routing key " + routingKey + " does not match eventType "
                    + env.eventType());
        }
        return env;
    }
}
