package com.thermogrid.simulator.sim;

import com.thermogrid.simulator.config.SimulatorProperties;
import com.thermogrid.simulator.physics.HealthState;
import com.thermogrid.simulator.physics.PhysicsModel;
import com.thermogrid.simulator.physics.RackSpec;
import com.thermogrid.simulator.topology.RackGrid;
import com.thermogrid.simulator.topology.ZoneSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live ground-truth state. The whole tick result is one immutable {@link Snapshot} that is swapped in with a single
 * volatile write, so readers never see a half-updated tick.
 */
public class SimState {

    /** Per-rack inputs and outputs. Maintenance racks keep their last temperature, 0 CPU and 0 kW. */
    public record RackState(double tempC, double cpuUnits, double powerKw, double heatSpikeKw) {
    }

    /** Per-zone cooling inputs. */
    public record ZoneState(double outputPct, HealthState health) {
    }

    public record Snapshot(long tick, Instant timestamp, Map<String, RackState> racks, Map<String, ZoneState> zones) {
        public Snapshot {
            racks = Collections.unmodifiableMap(new LinkedHashMap<>(racks));
            zones = Collections.unmodifiableMap(new LinkedHashMap<>(zones));
        }
    }

    private final RackGrid grid;
    private final PhysicsModel model;
    private final SimulatorProperties.Initial initial;
    private final Clock clock;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile Snapshot snapshot;

    public SimState(RackGrid grid, PhysicsModel model, SimulatorProperties.Initial initial, Clock clock) {
        this.grid = grid;
        this.model = model;
        this.initial = initial;
        this.clock = clock;
        this.snapshot = initialSnapshot();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public void replace(Snapshot next) {
        this.snapshot = next;
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void setPaused(boolean value) {
        paused.set(value);
    }

    /** Back to tick 0 with the configured initial values. Does not touch the paused flag. */
    public void reset() {
        this.snapshot = initialSnapshot();
    }

    private Snapshot initialSnapshot() {
        Map<String, RackState> racks = new LinkedHashMap<>();
        for (RackSpec r : grid.racks()) {
            if (r.active()) {
                double cpu = initial.cpuUnits();
                racks.put(r.id(), new RackState(initial.tempC(), cpu, model.powerKw(r, cpu, 0.0), 0.0));
            } else {
                racks.put(r.id(), new RackState(initial.tempC(), 0.0, 0.0, 0.0));
            }
        }
        Map<String, ZoneState> zones = new LinkedHashMap<>();
        for (ZoneSpec z : grid.zones()) {
            zones.put(z.id(), new ZoneState(initial.coolingOutputPct(), initial.health()));
        }
        return new Snapshot(0L, clock.instant(), racks, zones);
    }
}
