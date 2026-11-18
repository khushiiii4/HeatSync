package com.thermogrid.contracts;

/** Data of {@code workload.submitted}. */
public record WorkloadSubmittedData(
        long workloadId,
        String name,
        int cpuDemand,
        String priority) {
}