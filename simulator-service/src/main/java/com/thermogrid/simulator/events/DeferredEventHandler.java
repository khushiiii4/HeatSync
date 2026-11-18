package com.thermogrid.simulator.events;

import com.thermogrid.contracts.CoolingUnitStateData;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.WorkloadAssignedData;
import com.thermogrid.contracts.WorkloadRemovedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Placeholder: the events are recognised and logged at debug level, and the simulation state is NOT changed. */
@Component
public class DeferredEventHandler implements SimulatorEventHandler {

    private static final Logger log = LoggerFactory.getLogger(DeferredEventHandler.class);

    @Override
    public void onWorkloadAssigned(EventEnvelope<WorkloadAssignedData> event) {
        log.debug("workload.assigned ignored until Phase 4: {}", event.eventId());
    }

    @Override
    public void onWorkloadRemoved(EventEnvelope<WorkloadRemovedData> event) {
        log.debug("workload.removed ignored until Phase 4: {}", event.eventId());
    }

    @Override
    public void onCoolingUnitState(EventEnvelope<CoolingUnitStateData> event) {
        log.debug("cooling.unit.state ignored until Phase 6: {}", event.eventId());
    }
}
