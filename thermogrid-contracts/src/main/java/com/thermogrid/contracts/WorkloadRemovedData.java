package com.thermogrid.contracts;

/** Data of {@code workload.removed}. */
public record WorkloadRemovedData(
        long workloadId,
        String rackId,
        int cpuDemand) {
}