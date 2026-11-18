package com.thermogrid.contracts;

/** Data of {@code telemetry.rack.reading} (one per rack per tick). */
public record TelemetryRackReadingData(
        String rackId,
        String zoneId,
        double tempC,
        double powerKw,
        double cpuLoadPct) {
}