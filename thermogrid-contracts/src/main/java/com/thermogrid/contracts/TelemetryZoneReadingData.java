package com.thermogrid.contracts;

/** Data of {@code telemetry.zone.reading} (one per zone per tick). */
public record TelemetryZoneReadingData(
        String zoneId,
        double avgTempC,
        double maxTempC,
        double supplyTempC,
        int fanActualPct,
        String unitHealth) {
}