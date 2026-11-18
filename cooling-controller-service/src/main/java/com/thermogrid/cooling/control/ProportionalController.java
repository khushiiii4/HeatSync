package com.thermogrid.cooling.control;

import java.time.Duration;
import java.time.Instant;

/**
 * The cooling control law of plan section 6E for one zone reading. Pure: no I/O, the clock is a parameter.
 *
 * <pre>
 * hot     = ewma(zone.maxTempC, 0.5) + 5 * slopePerReading
 * raw     = 35 + 12 * (hot - target)
 * floor   = 30, or 60 while any unit has a confirmed FAULT
 * boost   = 100 CRITICAL (held 60 s), 70 WARNING, 50 WATCH (held 20 s after the alert clears)
 * desired = min(100, max(raw, floor, boost))
 * output  = move toward desired by at most 10 points
 * </pre>
 * The caller decides whether the move is large enough (2 points) to be saved and published.
 */
public class ProportionalController {

    /** Per-zone memory of the controller: the smoothed temperature and the alert boost with its hold timer. */
    public static final class ZoneControl {
        private Double ewma;
        private AlertLevel held = AlertLevel.NONE;
        private Instant holdUntil;

        public Double ewma() { return ewma; }
        public AlertLevel heldLevel() { return held; }
        public Instant holdUntil() { return holdUntil; }
    }

    /** Everything one step computed, for the action reason and for tests. */
    public record Step(double ewma, double slope, double hot, double raw, int floor, AlertLevel boostLevel, int boost,
                       int desired, int output) {
    }

    private final CoolingParams p;

    public ProportionalController(CoolingParams params) {
        this.p = params;
    }

    public Step step(ZoneControl z, double maxTempC, double targetC, int lastOutput, boolean anyUnitFault,
                     AlertLevel activeAlert, Instant now) {
        double previous = z.ewma == null ? maxTempC : z.ewma;
        double ewma = z.ewma == null ? maxTempC : p.ewmaAlpha() * maxTempC + (1.0 - p.ewmaAlpha()) * previous;
        double slope = z.ewma == null ? 0.0 : ewma - previous;
        z.ewma = ewma;

        double hot = ewma + p.slopeGain() * slope;
        double raw = p.base() + p.gain() * (hot - targetC);
        int floor = anyUnitFault ? p.floorFault() : p.floorNormal();
        AlertLevel boostLevel = updateBoost(z, activeAlert, now);
        int boost = boostOutput(boostLevel);

        double desiredD = Math.min(100.0, Math.max(raw, Math.max(floor, boost)));
        int desired = (int) Math.max(0, Math.min(100, Math.round(desiredD)));
        int delta = Math.max(-p.maxStep(), Math.min(p.maxStep(), desired - lastOutput));
        int output = Math.max(0, Math.min(100, lastOutput + delta));
        return new Step(ewma, slope, hot, raw, floor, boostLevel, boost, desired, output);
    }

    /**
     * The boost level in force. A higher or equal active alert takes over at once; when the active level falls, the
     * previous level is kept for its hold time (60 s for CRITICAL, 20 s for WARNING and WATCH) counted from the first
     * evaluation after the fall.
     */
    AlertLevel updateBoost(ZoneControl z, AlertLevel active, Instant now) {
        if (active.atLeast(z.held)) {
            z.held = active;
            z.holdUntil = null;
        } else {
            if (z.holdUntil == null) {
                z.holdUntil = now.plus(Duration.ofSeconds(holdSeconds(z.held)));
            }
            if (!now.isBefore(z.holdUntil)) {
                z.held = active;
                z.holdUntil = null;
            }
        }
        return z.held;
    }

    long holdSeconds(AlertLevel level) {
        return level == AlertLevel.CRITICAL ? p.holdCriticalSec() : p.holdWarningSec();
    }

    int boostOutput(AlertLevel level) {
        return switch (level) {
            case CRITICAL -> p.boostCritical();
            case WARNING -> p.boostWarning();
            case WATCH -> p.boostWatch();
            case NONE -> 0;
        };
    }
}
