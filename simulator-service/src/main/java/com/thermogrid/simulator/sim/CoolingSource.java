package com.thermogrid.simulator.sim;

import java.util.OptionalDouble;

/**
 * Fan output per zone as commanded by the cooling controller. Empty until the first cooling.unit.state for the zone
 * arrives, in which case the zone keeps its configured initial output.
 */
@FunctionalInterface
public interface CoolingSource {

    CoolingSource NONE = zoneId -> OptionalDouble.empty();

    OptionalDouble outputPct(String zoneId);
}
