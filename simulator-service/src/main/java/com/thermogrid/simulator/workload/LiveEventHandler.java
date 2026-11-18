package com.thermogrid.simulator.workload;

import com.thermogrid.contracts.CoolingUnitStateData;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.WorkloadAssignedData;
import com.thermogrid.contracts.WorkloadRemovedData;
import com.thermogrid.simulator.events.SimulatorEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Workload events move heat sources; cooling events set the fan output of a zone. */
@Component
public class LiveEventHandler implements SimulatorEventHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveEventHandler.class);

    private final WorkloadLoads loads;
    private final CoolingInputs cooling;

    public LiveEventHandler(WorkloadLoads loads, CoolingInputs cooling) {
        this.loads = loads;
        this.cooling = cooling;
    }

    @Override
    public void onWorkloadAssigned(EventEnvelope<WorkloadAssignedData> event) {
        WorkloadAssignedData d = event.data();
        boolean applied = loads.applyAssigned(d.workloadId(), d.toRackId(), d.cpuDemand(), d.version());
        log.debug("workload.assigned {} -> {} v{} {}", d.workloadId(), d.toRackId(), d.version(),
                applied ? "applied" : "ignored");
    }

    @Override
    public void onWorkloadRemoved(EventEnvelope<WorkloadRemovedData> event) {
        boolean known = loads.applyRemoved(event.data().workloadId());
        log.debug("workload.removed {} {}", event.data().workloadId(), known ? "applied" : "not a known heat source");
    }

    @Override
    public void onCoolingUnitState(EventEnvelope<CoolingUnitStateData> event) {
        CoolingUnitStateData d = event.data();
        boolean applied = cooling.apply(d.zoneId(), d.outputPct(), d.mode(), d.health(), d.seq(), event.occurredAt());
        log.debug("cooling.unit.state {} output {} seq {} {}", d.zoneId(), d.outputPct(), d.seq(),
                applied ? "applied" : "ignored");
    }
}
