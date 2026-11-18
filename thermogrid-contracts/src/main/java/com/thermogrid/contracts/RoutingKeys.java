package com.thermogrid.contracts;

/** Routing keys of the nine event types (plan sections 8.4 and 8.5). */
public final class RoutingKeys {
    public static final String TELEMETRY_RACK_READING = "telemetry.rack.reading";
    public static final String TELEMETRY_ZONE_READING = "telemetry.zone.reading";
    public static final String THERMAL_ALERT_RAISED = "thermal.alert.raised";
    public static final String THERMAL_ALERT_LOWERED = "thermal.alert.lowered";
    public static final String THERMAL_ALERT_CLEARED = "thermal.alert.cleared";
    public static final String WORKLOAD_SUBMITTED = "workload.submitted";
    public static final String WORKLOAD_ASSIGNED = "workload.assigned";
    public static final String WORKLOAD_REMOVED = "workload.removed";
    public static final String COOLING_UNIT_STATE = "cooling.unit.state";

    /** Binding pattern matching all three alert keys (queues q.cooling.alerts and q.scheduler.alerts). */
    public static final String THERMAL_ALERT_ALL = "thermal.alert.#";

    private RoutingKeys() {
    }
}