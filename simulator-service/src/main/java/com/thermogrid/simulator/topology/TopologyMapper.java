package com.thermogrid.simulator.topology;

import com.thermogrid.simulator.config.SimulatorProperties;
import com.thermogrid.simulator.physics.RackSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates the topology-service payload and builds the {@link RackGrid}. Fails with {@link InvalidTopologyException}. */
public final class TopologyMapper {

    private TopologyMapper() {
    }

    public static RackGrid toGrid(List<TopologyZoneView> payload, SimulatorProperties.Grid expected) {
        if (payload == null || payload.isEmpty()) {
            throw new InvalidTopologyException("no zones returned");
        }
        if (payload.size() != expected.zones()) {
            throw new InvalidTopologyException("expected " + expected.zones() + " zones but got " + payload.size());
        }
        List<ZoneSpec> zones = new ArrayList<>();
        List<RackSpec> racks = new ArrayList<>();
        Set<String> rackIds = new HashSet<>();
        Set<String> coords = new HashSet<>();
        Set<String> zoneIds = new HashSet<>();

        for (TopologyZoneView z : payload) {
            if (z == null || blank(z.id()) || z.rowIndex() == null || z.targetTempC() == null) {
                throw new InvalidTopologyException("zone with missing id, rowIndex or targetTempC: " + z);
            }
            if (!zoneIds.add(z.id())) {
                throw new InvalidTopologyException("duplicate zone id " + z.id());
            }
            if (z.racks() == null) {
                throw new InvalidTopologyException("zone " + z.id() + " has no racks list");
            }
            zones.add(new ZoneSpec(z.id(), z.name(), z.rowIndex(), z.targetTempC().doubleValue()));
            for (TopologyRackView r : z.racks()) {
                racks.add(toRack(z, r, expected, rackIds, coords));
            }
        }
        if (racks.size() != expected.expectedRacks()) {
            throw new InvalidTopologyException(
                    "expected " + expected.expectedRacks() + " racks but got " + racks.size());
        }
        racks.sort(Comparator.comparingInt(RackSpec::row).thenComparingInt(RackSpec::col));
        zones.sort(Comparator.comparingInt(ZoneSpec::rowIndex));
        return new RackGrid(zones, racks);
    }

    private static RackSpec toRack(TopologyZoneView z, TopologyRackView r, SimulatorProperties.Grid expected,
                                   Set<String> rackIds, Set<String> coords) {
        if (r == null || blank(r.id()) || blank(r.zoneId()) || r.rowIndex() == null || r.colIndex() == null
                || r.cpuCapacity() == null || r.idlePowerKw() == null || r.maxPowerKw() == null
                || blank(r.status())) {
            throw new InvalidTopologyException("rack with a missing field in zone " + z.id() + ": " + r);
        }
        if (!rackIds.add(r.id())) {
            throw new InvalidTopologyException("duplicate rack id " + r.id());
        }
        if (!r.zoneId().equals(z.id()) || !r.rowIndex().equals(z.rowIndex())) {
            throw new InvalidTopologyException("rack " + r.id() + " does not belong to zone " + z.id()
                    + " (zoneId=" + r.zoneId() + ", row=" + r.rowIndex() + ")");
        }
        if (r.rowIndex() < 1 || r.rowIndex() > expected.rows() || r.colIndex() < 1 || r.colIndex() > expected.cols()) {
            throw new InvalidTopologyException("rack " + r.id() + " is outside the " + expected.rows() + "x"
                    + expected.cols() + " grid: row " + r.rowIndex() + ", col " + r.colIndex());
        }
        if (!coords.add(r.rowIndex() + "," + r.colIndex())) {
            throw new InvalidTopologyException("two racks at row " + r.rowIndex() + ", col " + r.colIndex());
        }
        if (r.cpuCapacity() <= 0) {
            throw new InvalidTopologyException("rack " + r.id() + " has non-positive cpuCapacity");
        }
        double idle = r.idlePowerKw().doubleValue();
        double max = r.maxPowerKw().doubleValue();
        if (idle < 0 || max < idle) {
            throw new InvalidTopologyException(
                    "rack " + r.id() + " power range invalid: idle " + idle + ", max " + max);
        }
        boolean active;
        switch (r.status()) {
            case "ACTIVE" -> active = true;
            case "MAINTENANCE" -> active = false;
            default -> throw new InvalidTopologyException("rack " + r.id() + " has unknown status " + r.status());
        }
        return new RackSpec(r.id(), r.zoneId(), r.rowIndex(), r.colIndex(), r.cpuCapacity(), idle, max, active);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
