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
                case RoutingKeys.WORKLOAD_ASSIGNED -> handler.onWorkloadAssigned(
                        read(body, routingKey, new TypeReference<EventEnvelope<WorkloadAssignedData>>() { }));
                case RoutingKeys.WORKLOAD_REMOVED -> handler.onWorkloadRemoved(
                        read(body, routingKey, new TypeReference<EventEnvelope<WorkloadRemovedData>>() { }));
                case RoutingKeys.COOLING_UNIT_STATE -> handler.onCoolingUnitState(
                        read(body, routingKey, new TypeReference<EventEnvelope<CoolingUnitStateData>>() { }));
                default -> throw new IllegalArgumentException("Simulator does not consume " + routingKey);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed " + routingKey + " message", e);
        }
    }

    private <T> EventEnvelope<T> read(byte[] body, String routingKey, TypeReference<EventEnvelope<T>> type)
            throws IOException {
        EventEnvelope<T> env = mapper.readValue(body, type);
        if (!routingKey.equals(env.eventType())) {
            throw new IllegalArgumentException("Routing key " + routingKey + " does not match eventType "
                    + env.eventType());
        }
        return env;
    }
}
