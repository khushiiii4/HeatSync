package com.thermogrid.contracts;

/**
 * Data of {@code workload.assigned}. {@code fromRackId} is null for a first placement.
 * {@code reason} is INITIAL_PLACEMENT, THERMAL_WARNING, THERMAL_CRITICAL, COOLING_FAILURE,
 * REBALANCE or SEED. {@code migrationId} is a UUID string.
 */
public record WorkloadAssignedData(
        long workloadId,
        int cpuDemand,
        String priority,
        String fromRackId,
        String toRackId,
        String reason,
        String migrationId,
        int version) {
}