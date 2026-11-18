package com.thermogrid.scheduler.snapshot;

import com.thermogrid.scheduler.model.Model.ClusterView;

/** Source of a fresh cluster view; implemented by {@link ClusterSnapshotService}, replaceable in tests. */
@FunctionalInterface
public interface SnapshotSource {

    /** @throws SnapshotUnavailableException if topology or workloads cannot be read */
    ClusterView fetch();
}
