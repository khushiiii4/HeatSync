package com.thermogrid.simulator.physics;

/** Static facts about one rack, taken from topology-service at startup. */
public record RackSpec(
        String id,
        String zoneId,
        int row,
        int col,
        int cpuCapacity,
        double idlePowerKw,
        double maxPowerKw,
        boolean active) {
}
