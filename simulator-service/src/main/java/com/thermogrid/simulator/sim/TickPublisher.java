package com.thermogrid.simulator.sim;

import java.util.Set;

/** Called by the engine after a tick's state has been committed. Must never throw into the tick. */
public interface TickPublisher {

    TickPublisher NONE = (snapshot, droppedRackIds) -> { };

    /**
     * @param droppedRackIds racks with an active SENSOR_DROPOUT: their rack readings must not be published
     */
    void publish(SimState.Snapshot snapshot, Set<String> droppedRackIds);
}
