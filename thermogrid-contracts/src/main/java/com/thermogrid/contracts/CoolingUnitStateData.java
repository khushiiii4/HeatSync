package com.thermogrid.contracts;

/** Data of {@code cooling.unit.state} (on every change and as a 10 s heartbeat; {@code seq} increases per zone). */
public record CoolingUnitStateData(
        String zoneId,
        String mode,
        int outputPct,
        String health,
        double targetTempC,
        String reason,
        long seq) {
}