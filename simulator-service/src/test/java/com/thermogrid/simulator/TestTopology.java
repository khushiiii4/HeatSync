package com.thermogrid.simulator;

import com.thermogrid.simulator.config.SimulatorProperties;
import com.thermogrid.simulator.physics.HealthState;
import com.thermogrid.simulator.physics.PhysicsParams;
import com.thermogrid.simulator.topology.RackGrid;
import com.thermogrid.simulator.topology.TopologyMapper;
import com.thermogrid.simulator.topology.TopologyRackView;
import com.thermogrid.simulator.topology.TopologyZoneView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The seeded topology of Bundle 2.1 (Z1..Z4, R11..R44, 100 units, 2 kW idle, 10 kW max) and the plan constants. */
public final class TestTopology {

    /** Plan section 6A values, written out here on purpose so a config edit cannot silently change the tests. */
    public static final PhysicsParams PLAN = new PhysicsParams(18.0, 0.06, 0.12, 0.05, 0.05, 0.10, 1.0, 0.25, 0.0);
    public static final PhysicsParams PLAN_NO_NOISE =
            new PhysicsParams(18.0, 0.06, 0.12, 0.05, 0.0, 0.10, 1.0, 0.25, 0.0);

    public static final SimulatorProperties.Grid GRID = new SimulatorProperties.Grid(4, 4, 4);
    public static final SimulatorProperties.Initial INITIAL =
            new SimulatorProperties.Initial(26.0, 40.0, 60.0, HealthState.OK);

    private TestTopology() {
    }

    public static List<TopologyZoneView> payload() {
        return payload(Set.of());
    }

    public static List<TopologyZoneView> payload(Set<String> maintenance) {
        List<TopologyZoneView> zones = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            List<TopologyRackView> racks = new ArrayList<>();
            for (int col = 1; col <= 4; col++) {
                String id = "R" + row + col;
                racks.add(new TopologyRackView(id, "Z" + row, row, col, 100, new BigDecimal("2.00"),
                        new BigDecimal("10.00"), maintenance.contains(id) ? "MAINTENANCE" : "ACTIVE"));
            }
            zones.add(new TopologyZoneView("Z" + row, "Zone " + row, row, new BigDecimal("26.0"), racks));
        }
        return zones;
    }

    public static RackGrid grid() {
        return TopologyMapper.toGrid(payload(), GRID);
    }

    public static RackGrid grid(Set<String> maintenance) {
        return TopologyMapper.toGrid(payload(maintenance), GRID);
    }
}
