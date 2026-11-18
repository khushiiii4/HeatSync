package com.thermogrid.workload.event;

import java.time.Instant;

/**
 * An outbound domain event recorded inside a database transaction. It is turned into a RabbitMQ message only after
 * that transaction commits (see {@link WorkloadEventPublisher}); on rollback it is dropped.
 */
public record WorkloadEvent(String routingKey, Object data, Instant occurredAt) {
}
