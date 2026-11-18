package com.thermogrid.topology.api;

import com.thermogrid.topology.domain.Rack;
import java.math.BigDecimal;

public record RackDto(
        String id,
        String zoneId,
        int rowIndex,
        int colIndex,
        int cpuCapacity,
        BigDecimal idlePowerKw,
        BigDecimal maxPowerKw,
        String status) {

    public static RackDto from(Rack r) {
        return new RackDto(r.getId(), r.getZone().getId(), r.getRowIndex(), r.getColIndex(),
                r.getCpuCapacity(), r.getIdlePowerKw(), r.getMaxPowerKw(), r.getStatus().name());
    }
}