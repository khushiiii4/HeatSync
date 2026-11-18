package com.thermogrid.scheduler.snapshot;

/** The cluster state needed for a cycle could not be read (workload-service or topology down). */
public class SnapshotUnavailableException extends RuntimeException {

    public SnapshotUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
