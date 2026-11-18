package com.thermogrid.workload.domain;

/** Allowed values of {@code reason} on workload.assigned (plan section 8.5). */
public enum MigrationReason {
    INITIAL_PLACEMENT, THERMAL_WARNING, THERMAL_CRITICAL, COOLING_FAILURE, REBALANCE, SEED
}
