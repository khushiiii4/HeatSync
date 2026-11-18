package com.thermogrid.simulator.physics;

/** Cooling unit health. Its numeric factor is configuration, see {@link PhysicsParams}. */
public enum HealthState {
    OK, DEGRADED, FAULT
}
