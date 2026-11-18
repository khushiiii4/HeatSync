package com.thermogrid.simulator.sim;

import com.thermogrid.simulator.physics.PhysicsModel;
import com.thermogrid.simulator.physics.RackSpec;
import com.thermogrid.simulator.sim.SimState.RackState;
import com.thermogrid.simulator.sim.SimState.Snapshot;
import com.thermogrid.simulator.sim.SimState.ZoneState;
import com.thermogrid.simulator.topology.RackGrid;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Advances the simulation one tick at a time. There is exactly one scheduler thread, and {@link #tick()} is also
 * synchronized, so two ticks can never overlap. Every rack is computed from the previous tick's snapshot and the new
 * snapshot is published once, after all racks are done.
 */
@Component
public class SimulationEngine {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    private final RackGrid grid;
    private final PhysicsModel model;
    private final SimState state;
    private final Clock clock;

    public SimulationEngine(RackGrid grid, PhysicsModel model, SimState state, Clock clock) {
        this.grid = grid;
        this.model = model;
        this.state = state;
        this.clock = clock;
    }

    @Scheduled(fixedRateString = "${thermogrid.simulator.tick-ms}")
    void scheduledTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.error("Tick failed; state left at tick {}", state.snapshot().tick(), e);
        }
    }

    /** @return true if a tick was applied, false if the engine is paused */
    public synchronized boolean tick() {
        if (state.isPaused()) {
            return false;
        }
        Snapshot prev = state.snapshot();
        Map<String, RackState> next = new LinkedHashMap<>();
        for (RackSpec rack : grid.racks()) {
            RackState cur = prev.racks().get(rack.id());
            if (!rack.active()) {
                next.put(rack.id(), cur);
                continue;
            }
            ZoneState zone = prev.zones().get(rack.zoneId());
            double power = model.powerKw(rack, cur.cpuUnits(), cur.heatSpikeKw());
            double f = model.effectiveness(zone.outputPct(), zone.health());
            double t = model.nextTemperature(cur.tempC(), power, f, neighbourMean(rack, prev));
            next.put(rack.id(), new RackState(t, cur.cpuUnits(), power, cur.heatSpikeKw()));
        }
        state.replace(new Snapshot(prev.tick() + 1, clock.instant(), next, prev.zones()));
        return true;
    }

    /** Previous-tick mean over existing grid neighbours (maintenance racks included, at their frozen temperature). */
    private double neighbourMean(RackSpec rack, Snapshot prev) {
        List<RackSpec> nb = grid.neighboursOf(rack.id());
        if (nb.isEmpty()) {
            return prev.racks().get(rack.id()).tempC();
        }
        double sum = 0.0;
        for (RackSpec n : nb) {
            sum += prev.racks().get(n.id()).tempC();
        }
        return sum / nb.size();
    }

    public void pause() {
        state.setPaused(true);
    }

    public void resume() {
        state.setPaused(false);
    }

    public synchronized void reset() {
        state.reset();
    }
}
