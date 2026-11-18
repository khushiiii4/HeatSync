package com.thermogrid.simulator.workload;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Workload integration of the simulator. {@code listenerEnabled} switches the RabbitMQ consumer; {@code reconcileMs}
 * is the period of the full resync (plan: 60 s) and {@code retryMs} how often a failed first load is retried.
 */
@Validated
@ConfigurationProperties("thermogrid.simulator.workloads")
public record WorkloadSyncProperties(
        boolean listenerEnabled,
        @Positive long reconcileMs,
        @Positive long retryMs,
        @Positive long tombstoneTtlMs,
        @Positive int queueMaxLength) {
}
