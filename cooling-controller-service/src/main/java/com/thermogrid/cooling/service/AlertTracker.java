package com.thermogrid.cooling.service;

import com.thermogrid.cooling.control.AlertLevel;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The highest alert level per zone, built from thermal.alert.* events. Each event carries the new absolute level of one
 * rack (NORMAL means cleared), so the zone level is the maximum over its racks. An event older than the last one seen
 * for the same rack is ignored, which makes duplicate and out-of-order delivery harmless.
 *
 * <p>Known limit: the map lives in memory. After a restart it is empty until the next alert event; a rack already in
 * WARNING is not re-announced by telemetry, so its boost is missing until the level changes (the temperature term of
 * the control law still reacts to the heat itself).
 */
public class AlertTracker {

    private record RackAlert(AlertLevel level, Instant at) {
    }

    private final Map<String, Map<String, RackAlert>> zones = new HashMap<>();

    /** @return true if the event changed the stored level of the rack */
    public synchronized boolean apply(String zoneId, String rackId, String level, Instant occurredAt) {
        if (zoneId == null || rackId == null || occurredAt == null) {
            return false;
        }
        Map<String, RackAlert> racks = zones.computeIfAbsent(zoneId, z -> new HashMap<>());
        RackAlert old = racks.get(rackId);
        if (old != null && occurredAt.isBefore(old.at())) {
            return false;
        }
        AlertLevel now = AlertLevel.of(level);
        racks.put(rackId, new RackAlert(now, occurredAt));
        return old == null || old.level() != now;
    }

    public synchronized void clear() {
        zones.clear();
    }

    public synchronized AlertLevel zoneLevel(String zoneId) {
        AlertLevel max = AlertLevel.NONE;
        for (RackAlert a : zones.getOrDefault(zoneId, Map.of()).values()) {
            if (a.level().atLeast(max)) {
                max = a.level();
            }
        }
        return max;
    }
}
