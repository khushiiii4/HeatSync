package com.thermogrid.simulator.events;

import com.thermogrid.contracts.CoolingUnitStateData;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.WorkloadAssignedData;
import com.thermogrid.contracts.WorkloadRemovedData;

/**
 * What the simulator will do with the three events it consumes (plan 8.4: q.simulator.workloads and
 * q.simulator.cooling). The real behaviour arrives with Phase 4 (workload-service) and Phase 6 (cooling-controller).
 */
public interface SimulatorEventHandler {

    void onWorkloadAssigned(EventEnvelope<WorkloadAssignedData> event);

    void onWorkloadRemoved(EventEnvelope<WorkloadRemovedData> event);

    void onCoolingUnitState(EventEnvelope<CoolingUnitStateData> event);
}
