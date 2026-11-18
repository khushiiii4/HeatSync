package com.thermogrid.telemetry.service;

import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.contracts.ThermalAlertData;
import java.time.Instant;

/** A persisted alert change that must be announced on RabbitMQ after the transaction commits. */
public record AlertTransition(Kind kind, ThermalAlertData data, Instant occurredAt) {

    public enum Kind {
        RAISED(RoutingKeys.THERMAL_ALERT_RAISED),
        LOWERED(RoutingKeys.THERMAL_ALERT_LOWERED),
        CLEARED(RoutingKeys.THERMAL_ALERT_CLEARED);

        private final String routingKey;

        Kind(String routingKey) {
            this.routingKey = routingKey;
        }

        public String routingKey() {
            return routingKey;
        }
    }
}
