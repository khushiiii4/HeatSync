package com.thermogrid.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Common envelope for every RabbitMQ message (plan section 8.5).
 *
 * @param eventId    unique id of this message (UUID)
 * @param eventType  routing key of the event, e.g. {@code workload.assigned}
 * @param occurredAt time the fact happened (ISO-8601 UTC in JSON)
 * @param producer   name of the producing service, e.g. {@code workload-service}
 * @param version    schema version of {@code data}
 * @param data       the event payload
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        int version,
        T data) {
}