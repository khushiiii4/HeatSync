package com.thermogrid.simulator.workload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One element of {@code GET /internal/workloads} of workload-service. Unknown fields are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkloadView(long id, int cpuDemand, String priority, String status, String rackId, int version) {
}
