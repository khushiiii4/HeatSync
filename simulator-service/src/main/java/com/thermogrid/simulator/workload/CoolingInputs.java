package com.thermogrid.simulator.workload;

import com.thermogrid.simulator.sim.CoolingSource;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the cooling controller last told the simulator, per zone.
 *
 * <p>Only the commanded <b>output</b> drives the physics. The reported health is kept for display but is deliberately
 * not fed into the physics: the plant's health is decided by the injected failures alone. Applying the controller's
 * confirmed FAULT as well would double-count the degradation and would latch the fault on (a cleared injection could
 * never produce the OK readings the controller needs to recover).
 *
 * <p>Ordering: a message with a lower {@code seq} than the one applied is dropped unless it is also newer in time,
 * which is what a controller restart with a reset sequence looks like. Duplicates (same seq) are ignored. The last
 * output is kept for as long as the controller is silent, like a real fan holding its last setpoint.
 */
public class CoolingInputs implements CoolingSource {

    public record Applied(double outputPct, String mode, String health, long seq, Instant occurredAt) {
    }

    private final Map<String, Applied> zones = new ConcurrentHashMap<>();

    /** @return true if the message changed the state */
    public synchronized boolean apply(String zoneId, double outputPct, String mode, String health, long seq,
                                      Instant occurredAt) {
        if (zoneId == null || zoneId.isBlank() || Double.isNaN(outputPct) || occurredAt == null) {
            return false;
        }
        Applied cur = zones.get(zoneId);
        if (cur != null && (seq <= cur.seq()) && !occurredAt.isAfter(cur.occurredAt())) {
            return false;                       // duplicate or out of order
        }
        double clamped = Math.max(0.0, Math.min(100.0, outputPct));
        zones.put(zoneId, new Applied(clamped, mode, health, seq, occurredAt));
        return true;
    }

    @Override
    public OptionalDouble outputPct(String zoneId) {
        Applied a = zones.get(zoneId);
        return a == null ? OptionalDouble.empty() : OptionalDouble.of(a.outputPct());
    }

    public Optional<Applied> get(String zoneId) {
        return Optional.ofNullable(zones.get(zoneId));
    }
}
