package com.thermogrid.simulator.api;

import com.thermogrid.simulator.physics.RackSpec;
import com.thermogrid.simulator.sim.SimState;
import com.thermogrid.simulator.sim.SimState.RackState;
import com.thermogrid.simulator.sim.SimState.Snapshot;
import com.thermogrid.simulator.sim.SimState.ZoneState;
import com.thermogrid.simulator.topology.RackGrid;
import com.thermogrid.simulator.topology.ZoneSpec;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sim")
public class SimController {

    private final SimState state;
    private final RackGrid grid;

    public SimController(SimState state, RackGrid grid) {
        this.state = state;
        this.grid = grid;
    }

    @GetMapping("/state")
    public SimStateDto state() {
        Snapshot s = state.snapshot();
        List<SimStateDto.RackDto> racks = grid.racks().stream().map(r -> rack(r, s.racks().get(r.id()))).toList();
        List<SimStateDto.ZoneDto> zones = grid.zones().stream().map(z -> zone(z, s.zones().get(z.id()))).toList();
        return new SimStateDto(s.tick(), s.timestamp(), state.isPaused(), racks, zones, List.of());
    }

    private static SimStateDto.RackDto rack(RackSpec r, RackState st) {
        return new SimStateDto.RackDto(r.id(), r.zoneId(), r.row(), r.col(), r.active() ? "ACTIVE" : "MAINTENANCE",
                st.tempC(), st.cpuUnits(), r.cpuCapacity(), st.powerKw(), st.heatSpikeKw());
    }

    private static SimStateDto.ZoneDto zone(ZoneSpec z, ZoneState st) {
        return new SimStateDto.ZoneDto(z.id(), z.name(), z.rowIndex(), st.outputPct(), st.health());
    }
}
