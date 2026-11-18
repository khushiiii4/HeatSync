package com.thermogrid.simulator.topology;

import java.math.BigDecimal;

/** This service's own view of a rack in GET /internal/topology (plan: Feign DTOs are not shared). */
public record TopologyRackView(
        String id,
        String zoneId,
        Integer rowIndex,
        Integer colIndex,
        Integer cpuCapacity,
        BigDecimal idlePowerKw,
        BigDecimal maxPowerKw,
        String status) {
}
