package com.thermogrid.simulator.failure;

/** Plan section 7: the four failure types stored in failure_injection.type. */
public enum FailureType {
    /** Targets a zone. Cooling health becomes FAULT. */
    COOLING_FAILURE(true),
    /** Targets a zone. {@code param} is the remaining cooling efficiency, 0 &lt; param &lt; 1. */
    COOLING_DEGRADED(true),
    /** Targets a rack. Its rack readings are not published; the physics keeps running. */
    SENSOR_DROPOUT(false),
    /** Targets a rack. {@code param} is extra heat in kW. */
    HEAT_SPIKE(false);

    private final boolean zoneTarget;

    FailureType(boolean zoneTarget) {
        this.zoneTarget = zoneTarget;
    }

    public boolean targetsZone() {
        return zoneTarget;
    }
}
