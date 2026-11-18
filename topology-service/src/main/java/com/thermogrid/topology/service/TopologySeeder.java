package com.thermogrid.topology.service;

import com.thermogrid.topology.domain.CoolingZone;
import com.thermogrid.topology.domain.Rack;
import com.thermogrid.topology.repo.RackRepository;
import com.thermogrid.topology.repo.ZoneRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent seed of 4 zones and 16 racks (plan sections 1 and 7). Only missing rows are inserted;
 * existing rows (including operator changes such as MAINTENANCE or a new target temperature) are never touched.
 */
@Component
public class TopologySeeder {

    static final int ROWS = 4;
    static final int COLS = 4;

    private final ZoneRepository zones;
    private final RackRepository racks;

    public TopologySeeder(ZoneRepository zones, RackRepository racks) {
        this.zones = zones;
        this.racks = racks;
    }

    @Transactional
    public void seed() {
        for (int row = 1; row <= ROWS; row++) {
            String zoneId = "Z" + row;
            CoolingZone zone = zones.findById(zoneId)
                    .orElseGet(() -> zones.save(new CoolingZone(zoneId, "Zone " + zoneId.substring(1), Integer.parseInt(zoneId.substring(1)))));
            for (int col = 1; col <= COLS; col++) {
                String rackId = "R" + row + col;
                if (!racks.existsById(rackId)) {
                    racks.save(new Rack(rackId, zone, row, col));
                }
            }
        }
    }
}