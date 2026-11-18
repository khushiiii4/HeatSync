package com.thermogrid.workload.domain;

/** Lifecycle: PENDING (submitted, no rack) to RUNNING (on a rack) to REMOVED (terminal). */
public enum WorkloadStatus {
    PENDING, RUNNING, REMOVED
}
