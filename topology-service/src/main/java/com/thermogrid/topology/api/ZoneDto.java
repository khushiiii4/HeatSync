package com.thermogrid.topology.api;

import com.thermogrid.topology.domain.CoolingZone;
import java.math.BigDecimal;
import java.util.List;

public record ZoneDto(String id, String name, int rowIndex, BigDecimal targetTempC, List<RackDto> racks) {

    public static ZoneDto from(CoolingZone z, List<RackDto> racks) {
        return new ZoneDto(z.getId(), z.getName(), z.getRowIndex(), z.getTargetTempC(), racks);
    }
}