package com.thermogrid.simulator.topology;

import java.math.BigDecimal;
import java.util.List;

public record TopologyZoneView(String id, String name, Integer rowIndex, BigDecimal targetTempC,
                               List<TopologyRackView> racks) {
}
