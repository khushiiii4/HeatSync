package com.thermogrid.simulator.topology;

import com.thermogrid.simulator.physics.RackSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable rows x cols rack grid with up/down/left/right neighbour lookup. Racks are kept in the order given. */
public final class RackGrid {

    private final List<ZoneSpec> zones;
    private final List<RackSpec> racks;
    private final Map<String, RackSpec> byId;
    private final Map<String, List<RackSpec>> neighbours;

    RackGrid(List<ZoneSpec> zones, List<RackSpec> racks) {
        this.zones = List.copyOf(zones);
        this.racks = List.copyOf(racks);
        Map<String, RackSpec> ids = new LinkedHashMap<>();
        Map<Long, RackSpec> byCoord = new HashMap<>();
        for (RackSpec r : this.racks) {
            ids.put(r.id(), r);
            byCoord.put(key(r.row(), r.col()), r);
        }
        this.byId = Map.copyOf(ids);
        int[][] offsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        Map<String, List<RackSpec>> nb = new HashMap<>();
        for (RackSpec r : this.racks) {
            List<RackSpec> list = new ArrayList<>(4);
            for (int[] o : offsets) {
                RackSpec n = byCoord.get(key(r.row() + o[0], r.col() + o[1]));
                if (n != null) {
                    list.add(n);
                }
            }
            nb.put(r.id(), List.copyOf(list));
        }
        this.neighbours = Map.copyOf(nb);
    }

    private static long key(int row, int col) {
        return ((long) row << 32) | (col & 0xffffffffL);
    }

    public List<ZoneSpec> zones() {
        return zones;
    }

    public List<RackSpec> racks() {
        return racks;
    }

    public RackSpec rack(String id) {
        return byId.get(id);
    }

    /** Existing up/down/left/right neighbours only: a corner rack has 2, an edge rack 3, an inner rack 4. */
    public List<RackSpec> neighboursOf(String rackId) {
        return neighbours.get(rackId);
    }

    /** Same racks in another order. For tests that prove the update does not depend on iteration order. */
    public RackGrid withRackOrder(List<RackSpec> reordered) {
        return new RackGrid(zones, reordered);
    }
}
