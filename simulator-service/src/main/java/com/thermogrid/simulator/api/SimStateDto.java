package com.thermogrid.simulator.api;

import com.thermogrid.simulator.physics.HealthState;
import java.time.Instant;
import java.util.List;

/** Response of GET /api/sim/state. Immutable copies only; no internal state objects are exposed. */
public record SimStateDto(
        long tick,
        Instant timestamp,
        boolean paused,
        List<RackDto> racks,
        List<ZoneDto> zones,
        List<FailureDto> activeFailures) {

    public record RackDto(
            String id,
            String zoneId,
            int rowIndex,
            int colIndex,
            String status,
            double tempC,
            double cpuUnits,
            int cpuCapacity,
            double powerKw,
            double heatSpikeKw) {
    }

    public record ZoneDto(String id, String name, int rowIndex, double coolingOutputPct, HealthState health) {
    }

    /** Provisional shape; always empty until failure injection arrives in Bundle 2.3. */
    public record FailureDto(long id, String type, String targetId, Instant startedAt) {
    }
}
