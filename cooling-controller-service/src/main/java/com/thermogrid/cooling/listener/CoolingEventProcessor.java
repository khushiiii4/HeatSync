package com.thermogrid.cooling.listener;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.contracts.TelemetryZoneReadingData;
import com.thermogrid.contracts.ThermalAlertData;
import com.thermogrid.cooling.service.CoolingControlService;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * Maps a raw message and its routing key onto the contract records and hands it to the control loop. Malformed
 * messages raise {@link MalformedEventException}, which the listener retry policy never retries (plan 8.6).
 */
@Component
public class CoolingEventProcessor {

    /** Unparsable or mismatched message: dead-lettered at once. */
    public static class MalformedEventException extends RuntimeException {
        public MalformedEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final ObjectMapper mapper;
    private final CoolingControlService control;

    public CoolingEventProcessor(ObjectMapper mapper, CoolingControlService control) {
        this.mapper = mapper;
        this.control = control;
    }

    public void handle(String routingKey, byte[] body) {
        try {
            switch (routingKey) {
                case RoutingKeys.TELEMETRY_ZONE_READING -> control.onZoneReading(
                        read(body, routingKey, new TypeReference<EventEnvelope<TelemetryZoneReadingData>>() { }));
                case RoutingKeys.THERMAL_ALERT_RAISED, RoutingKeys.THERMAL_ALERT_LOWERED,
                        RoutingKeys.THERMAL_ALERT_CLEARED -> control.onAlert(
                        read(body, routingKey, new TypeReference<EventEnvelope<ThermalAlertData>>() { }));
                default -> throw new MalformedEventException("Cooling controller does not consume " + routingKey, null);
            }
        } catch (IOException e) {
            throw new MalformedEventException("Malformed " + routingKey + " message", e);
        }
    }

    private <T> EventEnvelope<T> read(byte[] body, String routingKey, TypeReference<EventEnvelope<T>> type)
            throws IOException {
        EventEnvelope<T> env = mapper.readValue(body, type);
        if (env == null || env.eventId() == null || env.data() == null || env.occurredAt() == null) {
            throw new MalformedEventException("Incomplete " + routingKey + " envelope", null);
        }
        if (!routingKey.equals(env.eventType())) {
            throw new MalformedEventException("Routing key " + routingKey + " does not match eventType "
                    + env.eventType(), null);
        }
        return env;
    }
}
