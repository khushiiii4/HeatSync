package com.thermogrid.simulator.sim;

import com.thermogrid.simulator.failure.FailureRecord;
import com.thermogrid.simulator.failure.FailureRegistry;
import com.thermogrid.simulator.failure.FailureSource;
import com.thermogrid.simulator.failure.FailureType;
import com.thermogrid.simulator.physics.HealthState;
import com.thermogrid.simulator.physics.PhysicsModel;
import com.thermogrid.simulator.physics.RackSpec;
import com.thermogrid.simulator.sim.SimState.RackState;
import com.thermogrid.simulator.sim.SimState.Snapshot;
import com.thermogrid.simulator.sim.SimState.ZoneState;
import com.thermogrid.simulator.topology.RackGrid;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Advances the simulation one tick at a time. There is exactly one scheduler thread, and {@link #tick()} is also
 * synchronized, so two ticks can never overlap. Every rack is computed from the previous tick's snapshot and the new
 * snapshot is published once, after all racks are done. Active failures are turned into the existing model inputs
 * (zone health, heat spike) before the physics runs; the formula itself is untouched.
 *
 * <p>Readings are published only after the new state is committed, and a publishing problem never changes the state.
 */
@Component
public class SimulationEngine {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    private final RackGrid grid;
    private final PhysicsModel model;
    private final SimState state;
    private final Clock clock;
    private final FailureSource failures;
    private final TickPublisher publisher;

    @Autowired
    public SimulationEngine(RackGrid grid, PhysicsModel model, SimState state, Clock clock, FailureRegistry failures,
                            ObjectProvider<TickPublisher> publisher) {
        this(grid, model, state, clock, failures, publisher.getIfAvailable(() -> TickPublisher.NONE));
    }

    public SimulationEngine(RackGrid grid, PhysicsModel model, SimState state, Clock clock, FailureSource failures,
                            TickPublisher publisher) {
        this.grid = grid;
        this.model = model;
        this.state = state;
        this.clock = clock;
        this.failures = failures;
        this.publisher = publisher;
    }

    public SimulationEngine(RackGrid grid, PhysicsModel model, SimState state, Clock clock) {
        this(grid, model, state, clock, FailureSource.NONE, TickPublisher.NONE);
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
        Instant now = clock.instant();
        List<FailureRecord> active = failures.activeAt(now);
        Snapshot prev = state.snapshot();

        Map<String, ZoneState> zones = effectiveZones(prev, active);
        Map<String, Double> spikes = new HashMap<>();
        Set<String> dropped = new HashSet<>();
        for (FailureRecord f : active) {
            if (f.type() == FailureType.HEAT_SPIKE) {
                spikes.merge(f.targetId(), f.param(), Double::sum);
            } else if (f.type() == FailureType.SENSOR_DROPOUT) {
                dropped.add(f.targetId());
            }
        }

        Map<String, RackState> next = new LinkedHashMap<>();
        for (RackSpec rack : grid.racks()) {
            RackState cur = prev.racks().get(rack.id());
            if (!rack.active()) {
                next.put(rack.id(), cur);
                continue;
            }
            ZoneState zone = zones.get(rack.zoneId());
            double spike = spikes.getOrDefault(rack.id(), 0.0);
            double power = model.powerKw(rack, cur.cpuUnits(), spike);
            double f = model.effectiveness(zone.outputPct(), zone.health(), zone.healthFactorOverride());
            double t = model.nextTemperature(cur.tempC(), power, f, neighbourMean(rack, prev));
            next.put(rack.id(), new RackState(t, cur.cpuUnits(), power, spike));
        }
        Snapshot committed = new Snapshot(prev.tick() + 1, now, next, zones);
        state.replace(committed);

        try {
            publisher.publish(committed, dropped);
        } catch (RuntimeException e) {
            log.warn("Publishing tick {} failed: {}", committed.tick(), e.toString());
        }
        return true;
    }

    /** Cooling failures become zone health / efficiency inputs. FAULT beats DEGRADED. */
    private Map<String, ZoneState> effectiveZones(Snapshot prev, List<FailureRecord> active) {
        Map<String, ZoneState> out = new LinkedHashMap<>();
        for (var e : prev.zones().entrySet()) {
            String zoneId = e.getKey();
            double outputPct = e.getValue().outputPct();
            boolean fault = false;
            Double degraded = null;
            for (FailureRecord f : active) {
                if (!f.targetId().equals(zoneId)) {
                    continue;
                }
                if (f.type() == FailureType.COOLING_FAILURE) {
                    fault = true;
                } else if (f.type() == FailureType.COOLING_DEGRADED) {
                    degraded = degraded == null ? f.param() : Math.min(degraded, f.param());
                }
            }
            if (fault) {
                out.put(zoneId, new ZoneState(outputPct, HealthState.FAULT));
            } else if (degraded != null) {
                out.put(zoneId, new ZoneState(outputPct, HealthState.DEGRADED, degraded));
            } else {
                out.put(zoneId, new ZoneState(outputPct, state.baseHealth()));
            }
        }
        return out;
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

    /**
     * RESET: clears active failures (history rows stay, marked cleared), restores initial values at tick 0 and
     * leaves the simulator running (not paused).
     */
    public synchronized void reset() {
        failures.clearAll(clock.instant());
        state.reset();
        state.setPaused(false);
    }
}
